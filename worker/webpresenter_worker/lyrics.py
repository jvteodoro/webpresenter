"""
Lyrics and timing from a sung recording.

Two modes, chosen by the server:
  - transcribe: the song has no lyrics yet; Whisper's lines become slides (two lines per slide,
    a new block after a pause), repeated blocks become one part (the most repeated is "Coro");
  - align: the song has lyrics; only the start of each slide is found, by aligning the known
    words with the transcribed ones (tolerant to recognition errors).

Recognition runs on the vocals (Demucs) when available: the accompaniment confuses it.
"""
from __future__ import annotations

import collections
import difflib
import re
import unicodedata
from dataclasses import dataclass, field

LINES_PER_SLIDE = 2
SLIDE_GAP_S = 2.5        # a pause this long between lines starts a new slide
BLOCK_GAP_S = 5.0        # and this long, a new block (verse/chorus)
INSTRUMENTAL_GAP_S = 8.0 # and this long, an instrumental slide between blocks


@dataclass
class Word:
    text: str
    start: float
    end: float
    phrase_start: bool = False  # first word of a phrase (a Whisper segment)
    probability: float = 1.0    # recognition confidence


@dataclass
class Line:
    words: list[Word] = field(default_factory=list)

    @property
    def text(self) -> str:
        return " ".join(w.text for w in self.words).strip()

    @property
    def start(self) -> float:
        return self.words[0].start

    @property
    def end(self) -> float:
        return self.words[-1].end

    @property
    def confidence(self) -> float:
        return sum(w.probability for w in self.words) / len(self.words)


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text.lower())
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9 ]+", " ", text).strip()


def tokens(text: str) -> list[str]:
    return normalize(text).split()


# ---------------------------------------------------------------------- recognition

def recognize(audio_path: str, model_name: str, language: str, progress, hint: str | None = None) -> list[Word]:
    """
    Words with their times, from faster-whisper, on the isolated vocals.

    No voice activity filter: it takes long sung notes for silence and drops whole stretches of the
    song. Segments Whisper itself finds unlikely to be speech, or that are only noise, are dropped.
    """
    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device="auto", compute_type="int8")
    segments, info = model.transcribe(audio_path, language=language, word_timestamps=True, vad_filter=False,
                                      condition_on_previous_text=False, beam_size=5,
                                      # The title as a hint for its words (an initial prompt leaks into the text).
                                      hotwords=hint or None)
    words: list[Word] = []
    duration = max(1.0, info.duration)
    for segment in segments:
        progress(min(1.0, segment.end / duration))
        text = segment.text.strip()
        if segment.no_speech_prob > 0.6 or len(normalize(text)) < 3 or segment.compression_ratio > 2.6:
            continue  # noise, hallucination or repetition loop
        first = True
        for w in segment.words or []:
            t = w.word.strip()
            if t:
                words.append(Word(t, float(w.start), float(w.end), phrase_start=first, probability=float(w.probability)))
                first = False
    return words


# ---------------------------------------------------------------------- transcribe mode

# Words that are capitalized inside a sentence (do not start a new line by themselves).
REVERENT = {"ti", "tu", "teu", "tua", "teus", "tuas", "senhor", "deus", "jesus", "cristo", "pai", "espirito", "santo",
            "ele", "seu", "sua", "seus", "suas", "rei", "salvador", "eu"}
MAX_LINE_CHARS = 42


def lines_from_words(words: list[Word]) -> list[Line]:
    """
    Sung lines: a new line at a new phrase, after punctuation, at a capitalized word starting a
    sentence, or after a pause; lines still too long are split where it is most balanced.
    """
    lines: list[Line] = []
    current = Line()
    for w in words:
        if current.words:
            previous = current.words[-1]
            gap = w.start - previous.end
            # A capitalized word starts a sentence, unless it is a reverent capital ("Teu", "Senhor")
            # sung straight on; after even a short pause it starts the line.
            capital = w.text[:1].isupper() and len(current.words) >= 3 \
                and (normalize(w.text) not in REVERENT or gap > 0.35)
            punctuation = previous.text[-1:] in ",.;!?" and len(current.words) >= 3
            if (w.phrase_start and len(current.words) >= 2) or gap > 0.8 or capital or punctuation:
                lines.append(current)
                current = Line()
        current.words.append(w)
    if current.words:
        lines.append(current)
    return merge_short_lines([part for line in lines for part in split_long(line)])


def split_long(line: Line) -> list[Line]:
    """Splits a line longer than MAX_LINE_CHARS at the most balanced word boundary, preferring one
    after a comma or before a capitalized word."""
    if len(line.text) <= MAX_LINE_CHARS or len(line.words) < 2:
        return [line]
    best, best_score = 1, None
    for k in range(1, len(line.words)):
        left = len(" ".join(w.text for w in line.words[:k]))
        score = abs(left - len(line.text) / 2) - (6 if line.words[k - 1].text[-1:] in ",;" else 0) \
            - (8 if line.words[k].text[:1].isupper() else 0)  # a capital usually starts the next phrase
        if best_score is None or score < best_score:
            best, best_score = k, score
    return split_long(Line(line.words[:best])) + split_long(Line(line.words[best:]))


def merge_short_lines(lines: list[Line]) -> list[Line]:
    """A line of one or two words joins the previous one when they fit together."""
    result: list[Line] = []
    for line in lines:
        if result and len(line.words) <= 2 and len(result[-1].text) + len(line.text) < MAX_LINE_CHARS \
                and line.start - result[-1].end < 2.0:
            result[-1].words.extend(line.words)
        else:
            result.append(line)
    return result


def clean(text: str) -> str:
    text = text.strip().rstrip(",;")
    return text[:1].upper() + text[1:] if text else text


SAME_LINE_RATIO = 0.7


def similar_lines(a: str, b: str) -> bool:
    return difflib.SequenceMatcher(None, normalize(a), normalize(b)).ratio() >= SAME_LINE_RATIO


def build_lyrics(words: list[Word]) -> tuple[dict, list[dict]]:
    """
    Parts, order and cues (one per slide of the sequence) from the recognized words.

    Lines sung more than once mark the chorus. Runs of repeated lines are chorus occurrences (a run
    is split where its first line comes back: the chorus sung twice); occurrences sharing most
    lines are the same part, written as its most confidently recognized occurrence, each occurrence
    keeping its own times. Other runs are verses. Slides hold up to two lines.
    """
    lines = lines_from_words(words)
    if not lines:
        return {"parts": [], "order": []}, []
    repeated = [any(similar_lines(line.text, other.text) for j, other in enumerate(lines) if j != i)
                for i, line in enumerate(lines)]

    runs: list[list[Line]] = []
    kinds: list[bool] = []
    for i, line in enumerate(lines):
        restart = bool(runs) and kinds[-1] and repeated[i] and len(runs[-1]) >= 2 \
            and similar_lines(line.text, runs[-1][0].text)
        if not runs or repeated[i] != kinds[-1] or line.start - runs[-1][-1].end >= BLOCK_GAP_S or restart:
            runs.append([line])
            kinds.append(repeated[i])
        else:
            runs[-1].append(line)
    merged_runs, merged_kinds = [], []
    for run, kind in zip(runs, kinds):
        if merged_runs and sum(len(l.words) for l in run) <= 3:
            merged_runs[-1].extend(run)  # a word or two is not a stanza
        else:
            merged_runs.append(list(run))
            merged_kinds.append(kind)
    runs, kinds = merged_runs, merged_kinds

    def same_run(a: list[Line], b: list[Line]) -> bool:
        return len(a) == len(b) and all(similar_lines(x.text, y.text) for x, y in zip(a, b))

    # Groups: occurrences that match line by line are the same part (so every slide of every
    # occurrence has its time); anything else is shown as it was sung.
    groups: list[int] = []
    members: list[list[int]] = []
    for i, run in enumerate(runs):
        match = next((g for g, m in enumerate(members) if same_run(run, runs[m[0]])), None)
        if match is None:
            members.append([i])
            match = len(members) - 1
        else:
            members[match].append(i)
        groups.append(match)

    def canonical(g: int) -> list[Line]:
        """The best recognized occurrence (they all have the same lines)."""
        return max((runs[i] for i in members[g]), key=lambda r: sum(l.confidence for l in r) / len(r))

    def voted(g: int, line: Line) -> str:
        """The wording most like the others among this line's occurrences (one bad hearing is outvoted)."""
        variants = [other.text for i in members[g] for other in runs[i] if similar_lines(line.text, other.text)]
        if len(variants) < 3:
            return line.text
        return max(variants, key=lambda v: sum(difflib.SequenceMatcher(None, normalize(v), normalize(o)).ratio()
                                               for o in variants))

    def slides_of(run: list[Line]) -> list[list[Line]]:
        slides: list[list[Line]] = []
        for line in run:
            if slides and len(slides[-1]) < LINES_PER_SLIDE and line.start - slides[-1][-1].end < SLIDE_GAP_S:
                slides[-1].append(line)
            else:
                slides.append([line])
        return slides

    chorus_groups = sorted((g for g in range(len(members)) if kinds[members[g][0]]),
                           key=lambda g: -len(members[g]))
    parts, ids, written = [], {}, {}
    verse = 0
    for g in range(len(members)):
        if g in chorus_groups:
            n = chorus_groups.index(g)
            pid, label, kind = ("c", "Coro", "CHORUS") if n == 0 else (f"c{n + 1}", f"Coro {n + 1}", "CHORUS")
        else:
            verse += 1
            pid, label, kind = f"v{verse}", f"Estrofe {verse}", "VERSE"
        ids[g] = pid
        written[g] = slides_of(canonical(g))
        parts.append({"id": pid, "kind": kind, "label": label,
                      "slides": [{"lines": [clean(voted(g, l)) for l in slide], "cue": None} for slide in written[g]]})
    parts.sort(key=lambda p: (p["kind"] != "VERSE", p["id"]))

    order: list[str] = []
    cues: list[dict] = []
    has_break = False
    for i, (run, g) in enumerate(zip(runs, groups)):
        if i > 0 and run[0].start - runs[i - 1][-1].end >= INSTRUMENTAL_GAP_S:
            if not has_break:
                parts.append({"id": "i", "kind": "INSTRUMENTAL", "label": "Instrumental",
                              "slides": [{"lines": [], "cue": None}]})
                has_break = True
            cues.append({"slide": len(cues), "startMs": int((runs[i - 1][-1].end + 1.0) * 1000)})
            order.append("i")
        order.append(ids[g])
        # Same lines as the written part: each slide starts with the same line of this occurrence.
        line_index = 0
        for slide in written[g]:
            cues.append({"slide": len(cues), "startMs": int(run[line_index].start * 1000)})
            line_index += len(slide)
    return {"parts": parts, "order": order}, monotonic(cues)


# ---------------------------------------------------------------------- align mode

def align(slides: list[str], words: list[Word]) -> tuple[list[dict], float]:
    """
    Start of each slide of a known sequence, by aligning its words with the recognized ones
    (Needleman-Wunsch, fuzzy word equality). Returns the cues and the share of matched words.
    """
    known: list[tuple[int, str]] = [(i, t) for i, text in enumerate(slides) for t in tokens(text)]
    heard = [normalize(w.text) for w in words]
    n, m = len(known), len(heard)
    if n == 0 or m == 0:
        return [], 0.0

    def similar(a: str, b: str) -> bool:
        return a == b or (len(a) > 2 and difflib.SequenceMatcher(None, a, b).ratio() >= 0.75)

    GAP_KNOWN, GAP_HEARD, MATCH, MISMATCH = -1, -0.4, 2, -1
    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    move = [[0] * (m + 1) for _ in range(n + 1)]  # 1 diagonal, 2 up (skip known), 3 left (skip heard)
    for i in range(1, n + 1):
        score[i][0] = i * GAP_KNOWN
        move[i][0] = 2
    for j in range(1, m + 1):
        score[0][j] = j * GAP_HEARD
        move[0][j] = 3
    for i in range(1, n + 1):
        ki = known[i - 1][1]
        row, prev = score[i], score[i - 1]
        for j in range(1, m + 1):
            diag = prev[j - 1] + (MATCH if similar(ki, heard[j - 1]) else MISMATCH)
            up = prev[j] + GAP_KNOWN
            left = row[j - 1] + GAP_HEARD
            best = max(diag, up, left)
            row[j] = best
            move[i][j] = 1 if best == diag else (2 if best == up else 3)

    # Walk back: the time of every known word matched to a heard one.
    times: dict[int, list[float]] = collections.defaultdict(list)
    matched = 0
    i, j = n, m
    while i > 0 and j > 0:
        mv = move[i][j]
        if mv == 1:
            if similar(known[i - 1][1], heard[j - 1]):
                times[known[i - 1][0]].append(words[j - 1].start)
                matched += 1
            i, j = i - 1, j - 1
        elif mv == 2:
            i -= 1
        else:
            j -= 1

    cues = []
    for slide in range(len(slides)):
        found = sorted(times.get(slide, []))
        if found and (len(found) >= 2 or not tokens(slides[slide]) or len(tokens(slides[slide])) <= 2):
            cues.append({"slide": slide, "startMs": int(found[0] * 1000)})
    return monotonic(cues), matched / n


def monotonic(cues: list[dict]) -> list[dict]:
    """Keeps only cues that move forward in time (the timing format requires it)."""
    result, last = [], -1
    for c in cues:
        if c["startMs"] > last:
            result.append(c)
            last = c["startMs"]
    return result

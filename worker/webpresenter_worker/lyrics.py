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

MAX_LINE_CHARS = 42
LINES_PER_SLIDE = 2
SLIDE_GAP_S = 2.5        # a pause this long between lines starts a new slide
BLOCK_GAP_S = 6.0        # and this long, a new block (verse/chorus), with an instrumental slide
SAME_BLOCK_RATIO = 0.75  # blocks this similar are the same part


@dataclass
class Word:
    text: str
    start: float
    end: float


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


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text.lower())
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9 ]+", " ", text).strip()


def tokens(text: str) -> list[str]:
    return normalize(text).split()


# ---------------------------------------------------------------------- recognition

def recognize(audio_path: str, model_name: str, language: str, progress) -> list[Word]:
    """Words with their times, from faster-whisper (voice activity filter on)."""
    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device="auto", compute_type="int8")
    segments, info = model.transcribe(audio_path, language=language, word_timestamps=True, vad_filter=True,
                                      condition_on_previous_text=False, beam_size=5)
    words: list[Word] = []
    duration = max(1.0, info.duration)
    for segment in segments:
        for w in segment.words or []:
            text = w.word.strip()
            if text:
                words.append(Word(text, float(w.start), float(w.end)))
        progress(min(1.0, segment.end / duration))
    return words


# ---------------------------------------------------------------------- transcribe mode

def lines_from_words(words: list[Word]) -> list[Line]:
    """Breaks the word stream into sung lines: at pauses, sentence ends, or when too long."""
    lines: list[Line] = []
    current = Line()
    for w in words:
        if current.words:
            gap = w.start - current.end
            too_long = len(current.text) + 1 + len(w.text) > MAX_LINE_CHARS
            sentence_end = current.words[-1].text[-1:] in ".!?;"
            if gap > 0.8 or too_long or sentence_end:
                lines.append(current)
                current = Line()
        current.words.append(w)
    if current.words:
        lines.append(current)
    return lines


def clean(text: str) -> str:
    text = text.strip().rstrip(",;")
    return text[:1].upper() + text[1:] if text else text


def build_lyrics(words: list[Word]) -> tuple[dict, list[dict]]:
    """Parts, order and cues (one per slide of the sequence) from the recognized words."""
    lines = lines_from_words(words)
    # Slides: up to two lines, a new one after a pause.
    slides: list[list[Line]] = []
    for line in lines:
        if slides and len(slides[-1]) < LINES_PER_SLIDE and line.start - slides[-1][-1].end < SLIDE_GAP_S:
            slides[-1].append(line)
        else:
            slides.append([line])
    # Blocks: consecutive slides, split at long pauses.
    blocks: list[list[list[Line]]] = []
    for slide in slides:
        if blocks and slide[0].start - blocks[-1][-1][-1].end < BLOCK_GAP_S:
            blocks[-1].append(slide)
        else:
            blocks.append([slide])

    def block_text(block):
        return normalize(" ".join(line.text for slide in block for line in slide))

    # Same part for similar blocks (a chorus sung several times).
    groups: list[int] = []
    representatives: list[int] = []
    for i, block in enumerate(blocks):
        text = block_text(block)
        match = next((g for g, rep in enumerate(representatives)
                      if difflib.SequenceMatcher(None, text, block_text(blocks[rep])).ratio() >= SAME_BLOCK_RATIO), None)
        if match is None:
            representatives.append(i)
            match = len(representatives) - 1
        groups.append(match)
    counts = collections.Counter(groups)
    chorus = next((g for g, n in counts.most_common(1) if n > 1), None)

    parts, ids = [], {}
    verse = 0
    for g, rep in enumerate(representatives):
        if g == chorus:
            pid, label, kind = "c", "Coro", "CHORUS"
        elif counts[g] > 1:
            pid, label, kind = f"c{g}", f"Coro {g}", "CHORUS"
        else:
            verse += 1
            pid, label, kind = f"v{verse}", f"Estrofe {verse}", "VERSE"
        ids[g] = pid
        parts.append({"id": pid, "kind": kind, "label": label,
                      "slides": [{"lines": [clean(l.text) for l in slide], "cue": None} for slide in blocks[rep]]})

    order: list[str] = []
    cues: list[dict] = []
    has_break = False
    for i, (block, g) in enumerate(zip(blocks, groups)):
        if i > 0:
            # Instrumental break between blocks: shown from shortly after the last line ends.
            if not has_break:
                parts.append({"id": "i", "kind": "INSTRUMENTAL", "label": "Instrumental",
                              "slides": [{"lines": [], "cue": None}]})
                has_break = True
            cues.append({"slide": len(cues), "startMs": int((blocks[i - 1][-1][-1].end + 1.0) * 1000)})
            order.append("i")
        order.append(ids[g])
        # Repeated parts keep their first wording; times come from this occurrence.
        rep_slides = len(blocks[representatives[g]])
        for k in range(rep_slides):
            slide = block[k] if k < len(block) else block[-1]
            cues.append({"slide": len(cues), "startMs": int(slide[0].start * 1000)})
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

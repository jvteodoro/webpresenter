"""
Text on the screen of a video, read frame by frame (OCR with Tesseract).

Lyrics shown in a video come out timed: each line is timed by the frames it shows in (first to last),
so its start is the moment it appears. Frames are streamed from ffmpeg as grey pixels, sampled
FPS times a second; a frame that looks like the previous one (after quantizing its grey levels) is
not read again, so a line that stays on screen costs one recognition.

Environment:
  VIDEO_TEXT_FPS    frames read per second (default 2)
  VIDEO_TEXT_WIDTH  width the frames are scaled to before reading (default 1280)
  TESSERACT_LANG    language data (default por; the Docker image installs it)
  TESSERACT_PSM     page segmentation mode (default 6: a uniform block of text)
"""
from __future__ import annotations

import difflib
import hashlib
import os
import re
import subprocess
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from .jobs import JobFailed, Reporter
from .lyrics import Word

FPS = float(os.environ.get("VIDEO_TEXT_FPS", "2"))
WIDTH = int(os.environ.get("VIDEO_TEXT_WIDTH", "1280"))
LANG = os.environ.get("TESSERACT_LANG", "por")
PSM = os.environ.get("TESSERACT_PSM", "6")

MIN_CONFIDENCE = 55   # Tesseract's confidence (0-100) below which a word is left out
GAP_SECONDS = 1.0     # a line gone from the screen for this long has ended; its return is a new line
MIN_LETTERS = 3       # shorter readings are noise
MIN_FRAMES = 2        # a line must show in this many frames

# Quantizing grey levels to 16 steps: frames that differ only by compression noise are the same frame.
QUANTIZE = bytes((value >> 4) << 4 for value in range(256))


@dataclass
class Track:
    """A line on the screen: its readings (most common first), and the frames it shows in."""

    first: float
    last: float
    seen: int = 1
    readings: Counter = field(default_factory=Counter)

    @property
    def text(self) -> str:
        return self.readings.most_common(1)[0][0]


def normalize(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text.lower())
    kept = "".join(c for c in decomposed if not unicodedata.combining(c) and (c.isalnum() or c.isspace()))
    return re.sub(r"\s+", " ", kept).strip()


def same_line(a: str, b: str) -> bool:
    """
    Two readings of one line differ only by recognition noise: about one character in eight at most.
    (A ratio would merge two different lines that share their beginning, "… um" and "… dois".)
    """
    if a == b:
        return True
    longest = max(len(a), len(b))
    matched = sum(block.size for block in difflib.SequenceMatcher(None, a, b, autojunk=False).get_matching_blocks())
    return longest - matched <= max(1, longest // 8)


def tracks(frames: Iterable[tuple[float, list[str]]]) -> list[Track]:
    """
    The lines on the screen over time. A reading joins the line on screen that it resembles; a line
    that was not read for GAP_SECONDS has ended.
    """
    ended: list[Track] = []
    active: list[Track] = []
    for t, lines in frames:
        still = []
        for track in active:
            (still if track.last >= t - GAP_SECONDS else ended).append(track)
        active = still
        for line in lines:
            key = normalize(line)
            if len(letters(key)) < MIN_LETTERS:
                continue
            match = next((tr for tr in active if same_line(normalize(tr.text), key)), None)
            if match is None:
                active.append(Track(first=t, last=t, readings=Counter({line.strip(): 1})))
            elif match.last != t:  # a second reading of one line in one frame counts once
                match.last = t
                match.seen += 1
                match.readings[line.strip()] += 1
    return ended + active


def letters(text: str) -> str:
    return "".join(c for c in text if c.isalpha())


def timed_words(frames: Iterable[tuple[float, list[str]]]) -> list[Word]:
    """
    Words of the lines that stayed long enough to be lyrics, in the order they appear. Every word of a
    line starts when the line first shows; the first word of each line starts a phrase.
    """
    words: list[Word] = []
    kept = [t for t in tracks(frames) if t.seen >= MIN_FRAMES and len(letters(normalize(t.text))) >= MIN_LETTERS]
    for track in sorted(kept, key=lambda t: (t.first, t.last)):
        end = track.last + 1 / FPS
        for i, token in enumerate(track.text.split()):
            words.append(Word(token, track.first, end, phrase_start=i == 0, probability=0.9))
    return words


def tsv_lines(tsv: str) -> list[str]:
    """
    The lines of a Tesseract TSV output, top to bottom. Words under MIN_CONFIDENCE are left out.
    Columns: level, page, block, paragraph, line, word, left, top, width, height, conf, text.
    """
    lines: dict[tuple[str, str, str], tuple[int, list[tuple[int, str]]]] = {}
    for row in tsv.splitlines()[1:]:
        cols = row.split("\t")
        if len(cols) < 12 or cols[0] != "5":
            continue
        try:
            confidence = float(cols[10])
            left, top = int(cols[6]), int(cols[7])
        except ValueError:
            continue
        text = cols[11].strip()
        if confidence < MIN_CONFIDENCE or not text:
            continue
        key = (cols[2], cols[3], cols[4])
        first_top, words = lines.get(key, (top, []))
        words.append((left, text))
        lines[key] = (min(first_top, top), words)
    ordered = sorted(lines.values(), key=lambda entry: entry[0])
    return [" ".join(text for _, text in sorted(words)) for _, words in ordered]


def read_lines(video: Path, workdir: Path, report: Reporter, start: int, end: int) -> list[tuple[float, list[str]]]:
    """(time in seconds, lines on the screen) for each frame sampled from the video."""
    width, height = _video_size(video)
    out_width = min(WIDTH, width) // 2 * 2
    out_height = max(2, round(height * out_width / width) // 2 * 2)
    duration = _duration(video)
    expected = max(1, int(duration * FPS)) if duration else None

    command = ["ffmpeg", "-v", "error", "-i", str(video), "-vf", f"fps={FPS},scale={out_width}:{out_height}",
               "-pix_fmt", "gray", "-f", "rawvideo", "pipe:1"]
    size = out_width * out_height
    readings: dict[bytes, list[str]] = {}
    frames: list[tuple[float, list[str]]] = []
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        assert process.stdout is not None
        index = 0
        while True:
            report.check()
            pixels = _read_exactly(process.stdout, size)
            if pixels is None:
                break
            signature = hashlib.blake2b(pixels.translate(QUANTIZE), digest_size=16).digest()
            if signature not in readings:
                readings[signature] = _read_frame(pixels, out_width, out_height, workdir)
            frames.append((index / FPS, readings[signature]))
            index += 1
            if expected:
                done = min(expected, index)
                report.progress(start + (end - start) * done // expected, f"Lendo o texto do vídeo {done * 100 // expected}%")
        code = process.wait()
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    report.check()
    if code != 0:
        detail = (process.stderr.read() if process.stderr else b"").decode(errors="replace").strip().splitlines()
        raise JobFailed(f"Não foi possível ler os quadros do vídeo: {detail[-1] if detail else code}")
    return frames


def _read_frame(pixels: bytes, width: int, height: int, workdir: Path) -> list[str]:
    image = workdir / "frame.pgm"
    image.write_bytes(b"P5\n%d %d\n255\n" % (width, height) + pixels)
    result = subprocess.run(["tesseract", str(image), "stdout", "-l", LANG, "--psm", PSM, "tsv"],
                            capture_output=True, text=True, errors="replace")
    if result.returncode != 0:
        raise JobFailed(f"O reconhecimento de texto falhou: {result.stderr.strip()[:200]}")
    return tsv_lines(result.stdout)


def _read_exactly(stream, size: int) -> bytes | None:
    data = bytearray()
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk:
            return None
        data.extend(chunk)
    return bytes(data)


def _video_size(video: Path) -> tuple[int, int]:
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "csv=p=0:s=x", str(video)], capture_output=True, text=True)
    try:
        width, height = (int(v) for v in probe.stdout.strip().split("x")[:2])
    except ValueError:
        raise JobFailed("O arquivo não tem uma imagem de vídeo para ler a letra")
    if width <= 0 or height <= 0:
        raise JobFailed("O arquivo não tem uma imagem de vídeo para ler a letra")
    return width, height


def _duration(video: Path) -> float:
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                            str(video)], capture_output=True, text=True)
    try:
        return float(probe.stdout.strip())
    except ValueError:
        return 0.0

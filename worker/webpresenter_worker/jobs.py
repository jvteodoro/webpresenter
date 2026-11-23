"""The job types. Each runs in a worker thread and reports through ``Reporter``."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import requests

from .messages import JobRequest

CHUNK = 1 << 20


class Cancelled(Exception):
    """The job was cancelled from the panel."""


class JobFailed(Exception):
    """A failure with a message meant for people (Portuguese, shown in the queue)."""


@dataclass
class Reporter:
    """Progress callback plus the cancellation flag of the current job."""

    progress: Callable[[int, str], None]
    cancelled: threading.Event

    def check(self) -> None:
        if self.cancelled.is_set():
            raise Cancelled()


@dataclass
class Result:
    path: Path
    filename: str
    title: str | None = None


# ---------------------------------------------------------------------- transfer helpers

def download(url: str, target: Path, report: Reporter, start: int, end: int, label: str) -> None:
    with requests.get(url, stream=True, timeout=60) as response:
        if response.status_code != 200:
            raise JobFailed(f"Não foi possível obter o arquivo de entrada (HTTP {response.status_code})")
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        with open(target, "wb") as out:
            for chunk in response.iter_content(CHUNK):
                report.check()
                out.write(chunk)
                done += len(chunk)
                if total:
                    report.progress(start + (end - start) * done // total, label)


def upload(url: str, source: Path) -> None:
    size = source.stat().st_size
    with open(source, "rb") as data:
        response = requests.put(url, data=data, headers={"Content-Length": str(size)}, timeout=3600)
    if response.status_code not in (200, 201, 204):
        raise JobFailed(f"Não foi possível enviar o resultado (HTTP {response.status_code})")


def safe_filename(title: str, ext: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', " ", title).strip() or "arquivo"
    return f"{name[:150]}.{ext}"


# ---------------------------------------------------------------------- YouTube import

def js_runtimes() -> dict[str, dict]:
    """YouTube needs a JavaScript runtime for yt-dlp; use whichever is installed."""
    runtimes = {}
    for name in ("deno", "node", "bun"):
        if shutil.which(name):
            runtimes[name] = {}
    return runtimes


def youtube_import(job: JobRequest, workdir: Path, report: Reporter) -> Result:
    import yt_dlp

    url = job.params["url"]
    video = job.params.get("format") == "VIDEO"

    def hook(d: dict) -> None:
        report.check()
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total:
                pct = int(d.get("downloaded_bytes", 0) * 100 / total)
                report.progress(min(85, 5 + pct * 80 // 100), f"Baixando {pct}%")
        elif d.get("status") == "finished":
            report.progress(88, "Convertendo")

    # Options built as yt-dlp's command line builds them: its defaults are what keeps working with
    # YouTube (a hand-made option dict downloaded the page but got 403 on the media).
    args = ["--no-playlist", "--restrict-filenames", "--quiet", "--no-progress", "--no-warnings",
            "-o", str(workdir / "%(id)s.%(ext)s")]
    for runtime in js_runtimes():
        args += ["--js-runtimes", runtime]
    if video:
        args += ["-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/b[height<=1080]/b",
                 "--merge-output-format", "mp4"]
    else:
        args += ["-f", "bestaudio[ext=m4a]/bestaudio/best", "-x", "--audio-format", "m4a"]
    options = yt_dlp.parse_options(args).ydl_opts
    options["progress_hooks"] = [hook]

    report.progress(2, "Consultando o link")
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
    except Cancelled:
        raise
    except yt_dlp.utils.DownloadError as e:
        if report.cancelled.is_set():
            raise Cancelled() from e
        first = str(e).replace("ERROR: ", "").splitlines()[0]
        raise JobFailed(f"Não foi possível baixar: {first}") from e
    report.check()

    if info.get("_type") == "playlist":
        raise JobFailed("O link é de uma playlist; use o link de um vídeo")
    ext = "mp4" if video else "m4a"
    files = sorted(workdir.glob(f"*.{ext}"), key=lambda p: p.stat().st_size, reverse=True)
    if not files:
        raise JobFailed("O download terminou sem gerar um arquivo")
    title = info.get("title") or files[0].stem
    return Result(files[0], safe_filename(title, ext), title)


# ---------------------------------------------------------------------- vocal separation

TQDM_PERCENT = re.compile(r"(\d{1,3})%\|")


def run_demucs(source: Path, workdir: Path, report: Reporter, start: int, end: int, label: str) -> dict[str, Path]:
    """Splits vocals from the rest with Demucs; returns {"vocals": …, "no_vocals": …} (WAV)."""
    model = os.environ.get("DEMUCS_MODEL", "htdemucs")
    command = [sys.executable, "-m", "webpresenter_worker.demucs_runner", "--two-stems", "vocals", "-n", model,
               "-o", str(workdir / "out"), str(source)]
    device = os.environ.get("DEMUCS_DEVICE")  # cpu or cuda; default: cuda when available
    if device:
        command += ["-d", device]
    report.progress(start, f"{label} (pode levar alguns minutos)")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
                               errors="replace")
    tail: list[str] = []
    watcher = threading.Thread(target=_kill_on_cancel, args=(process, report.cancelled), daemon=True)
    watcher.start()
    buffer = ""
    assert process.stdout is not None
    while True:
        char = process.stdout.read(1)
        if not char:
            break
        if char in "\r\n":
            line, buffer = buffer.strip(), ""
            if not line:
                continue
            tail = (tail + [line])[-20:]
            match = TQDM_PERCENT.search(line)
            if match:
                pct = int(match.group(1))
                report.progress(start + pct * (end - start) // 100, f"{label} {pct}%")
        else:
            buffer += char
    code = process.wait()
    report.check()
    if code != 0:
        raise JobFailed(f"O Demucs falhou: {_failure(code, tail)}")
    stems = {p.stem: p for p in (workdir / "out").glob("*/*/*.wav")}
    if "vocals" not in stems or "no_vocals" not in stems:
        raise JobFailed("O Demucs não gerou as faixas separadas")
    return stems


def separate_playback(job: JobRequest, workdir: Path, report: Reporter) -> Result:
    if not job.input_url:
        raise JobFailed("Trabalho sem arquivo de entrada")
    source = workdir / "input"
    report.progress(1, "Baixando a gravação")
    download(job.input_url, source, report, 1, 8, "Baixando a gravação")
    stems = run_demucs(source, workdir, report, 10, 88, "Separando as vozes")
    report.progress(90, "Codificando o playback")
    target = workdir / "playback.m4a"
    encode = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(stems["no_vocals"]), "-c:a", "aac", "-b:a", "192k",
                             "-movflags", "+faststart", str(target)], capture_output=True, text=True)
    if encode.returncode != 0:
        raise JobFailed(f"Não foi possível codificar o playback: {encode.stderr.strip()[:300]}")
    return Result(target, "playback.m4a")


# ---------------------------------------------------------------------- lyrics and timing

def transcribe_lyrics(job: JobRequest, workdir: Path, report: Reporter) -> Result:
    """
    Lyrics and slide times from a sung recording: transcribed (song without lyrics) or aligned
    (params.slides: the text of each slide of the song, in singing order).
    """
    from . import lyrics

    if not job.input_url:
        raise JobFailed("Trabalho sem arquivo de entrada")
    source = workdir / "input"
    report.progress(1, "Baixando a gravação")
    download(job.input_url, source, report, 1, 5, "Baixando a gravação")

    audio = source
    if os.environ.get("LYRICS_SEPARATE", "1") != "0":
        audio = run_demucs(source, workdir, report, 6, 45, "Isolando a voz")["vocals"]
    report.check()

    model = os.environ.get("WHISPER_MODEL", "medium")
    language = job.params.get("language") or "pt"
    report.progress(46, "Reconhecendo a letra")

    def heard(fraction: float) -> None:
        report.check()
        report.progress(46 + int(fraction * 46), f"Reconhecendo a letra {int(fraction * 100)}%")

    words = lyrics.recognize(str(audio), model, language, heard, job.params.get("title"))
    if not words:
        raise JobFailed("Nenhuma palavra reconhecida na gravação")
    report.progress(94, "Montando os slides")

    known = job.params.get("slides")
    if known:
        cues, matched = lyrics.align(known, words)
        if not cues:
            raise JobFailed("A letra da música não foi encontrada na gravação")
        result = {"mode": "aligned", "cues": cues, "confidence": round(matched, 2), "language": language}
    else:
        parsed, cues = lyrics.build_lyrics(words)
        result = {"mode": "transcribed", "lyrics": parsed, "cues": cues, "confidence": None, "language": language}
    target = workdir / "lyrics.json"
    target.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return Result(target, "lyrics.json")


def _failure(code: int, tail: list[str]) -> str:
    """The most useful line of a failed run (not a progress bar), or how the process ended."""
    lines = [l for l in tail if not TQDM_PERCENT.search(l)]
    detail = next((l for l in reversed(lines) if "Error" in l or "error" in l or "Exception" in l), None)
    if detail:
        return detail[:300]
    if code < 0 or code > 128:
        signal_number = -code if code < 0 else code - 128
        return f"o processo foi encerrado pelo sistema (sinal {signal_number})"
    return (lines[-1] if lines else f"código de saída {code}")[:300]


def _kill_on_cancel(process: subprocess.Popen, cancelled: threading.Event) -> None:
    while process.poll() is None:
        if cancelled.wait(0.5):
            process.terminate()
            try:
                process.wait(10)
            except subprocess.TimeoutExpired:
                process.kill()
            return


HANDLERS = {"YOUTUBE_IMPORT": youtube_import, "SEPARATE_PLAYBACK": separate_playback,
            "TRANSCRIBE_LYRICS": transcribe_lyrics}

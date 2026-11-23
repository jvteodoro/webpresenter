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
from urllib.parse import urlsplit, urlunsplit

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
    path: Path | None
    filename: str | None
    title: str | None = None
    files: dict[str, Path] | None = None  # several results: output name -> file


def many(files: dict[str, Path]) -> Result:
    return Result(None, None, None, files)


# ---------------------------------------------------------------------- transfer helpers

def reach(url: str) -> tuple[str, dict[str, str]]:
    """
    Where to send a work link, and headers. A worker on another computer reaches the media store
    through a relay at another address (MEDIA_STORE_ADDRESS, e.g. http://10.66.0.3:18333): the link
    goes there, with its original Host header, which its signature covers.
    """
    relay = os.environ.get("MEDIA_STORE_ADDRESS", "").rstrip("/")
    if not relay:
        return url, {}
    parts = urlsplit(url)
    target = urlsplit(relay)
    return urlunsplit((target.scheme, target.netloc, parts.path, parts.query, "")), {"Host": parts.netloc}


def download(url: str, target: Path, report: Reporter, start: int, end: int, label: str) -> None:
    url, headers = reach(url)
    with requests.get(url, headers=headers, stream=True, timeout=60) as response:
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
    url, headers = reach(url)
    with open(source, "rb") as data:
        response = requests.put(url, data=data, headers={**headers, "Content-Length": str(size)}, timeout=3600)
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


def run_demucs(source: Path, workdir: Path, report: Reporter, start: int, end: int, label: str,
               two_stems: bool = True, int24: bool = False) -> dict[str, Path]:
    """
    Separates a recording with Demucs (WAV results). Two stems: {"vocals", "no_vocals"}; otherwise
    the model's four: {"vocals", "drums", "bass", "other"}.
    """
    model = os.environ.get("DEMUCS_MODEL", "htdemucs")
    out = workdir / ("out" if two_stems else "stems")
    command = [sys.executable, "-m", "webpresenter_worker.demucs_runner", "-n", model, "-o", str(out)]
    if two_stems:
        command += ["--two-stems", "vocals"]
    if int24:
        command += ["--int24"]
    device = os.environ.get("DEMUCS_DEVICE")  # cpu or cuda; default: cuda when available
    if device:
        command += ["-d", device]
    segment = os.environ.get("DEMUCS_SEGMENT")  # seconds per chunk: less GPU memory (e.g. 7 on 4 GB)
    if segment:
        command += ["--segment", segment]
    command.append(str(source))
    report.progress(start, f"{label} (pode levar alguns minutos)")
    run_with_progress(command, report, start, end, label, "O Demucs falhou")
    stems = {p.stem: p for p in out.glob("*/*/*.wav")}
    expected = {"vocals", "no_vocals"} if two_stems else {"vocals", "drums", "bass", "other"}
    if not expected <= stems.keys():
        raise JobFailed("O Demucs não gerou as faixas separadas")
    return stems


def run_with_progress(command: list[str], report: Reporter, start: int, end: int, label: str, failure: str,
                      env: dict[str, str] | None = None) -> None:
    """Runs a tool that prints tqdm progress bars, mapping its percentage to start..end."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
                               errors="replace", env=env)
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
        raise JobFailed(f"{failure}: {_failure(code, tail)}")


# Codecs that keep every bit: a source like this keeps its separated tracks lossless (FLAC).
LOSSLESS = {"flac", "alac", "wavpack", "ape", "tta", "mlp", "truehd"}


def is_lossless(path: Path) -> bool:
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name",
                            "-of", "json", str(path)], capture_output=True, text=True)
    try:
        codec = json.loads(probe.stdout)["streams"][0]["codec_name"]
    except (ValueError, KeyError, IndexError):
        return False
    return codec in LOSSLESS or codec.startswith("pcm_")


def encode_track(inputs: list[Path], target_stem: Path, lossless: bool) -> Path:
    """
    One track from one or more WAV files (summed, as the model's stems add up to the mix): FLAC
    when the source was lossless, otherwise AAC (TRACK_BITRATE, default 160k; the source was already
    lossy, so a lossless copy would only take space). AAC in MP4 plays in every browser and phone.
    """
    target = target_stem.with_suffix(".flac" if lossless else ".m4a")
    command = ["ffmpeg", "-v", "error", "-y"]
    for path in inputs:
        command += ["-i", str(path)]
    if len(inputs) > 1:
        command += ["-filter_complex", f"amix=inputs={len(inputs)}:normalize=0"]
    if lossless:
        command += ["-c:a", "flac", "-compression_level", "8"]
    else:
        command += ["-c:a", "aac", "-b:a", os.environ.get("TRACK_BITRATE", "160k"), "-movflags", "+faststart"]
    encode = subprocess.run(command + [str(target)], capture_output=True, text=True)
    if encode.returncode != 0:
        raise JobFailed(f"Não foi possível codificar {target_stem.name}: {encode.stderr.strip()[:300]}")
    return target


def separate_playback(job: JobRequest, workdir: Path, report: Reporter) -> Result:
    """
    Separates a song recording into its tracks: vocals, drums, bass and other instruments, plus the
    playback (everything but the vocals).
    """
    if not job.input_url:
        raise JobFailed("Trabalho sem arquivo de entrada")
    source = workdir / "input"
    report.progress(1, "Baixando a gravação")
    download(job.input_url, source, report, 1, 8, "Baixando a gravação")
    lossless = is_lossless(source)
    stems = run_demucs(source, workdir, report, 10, 86, "Separando as faixas", two_stems=False, int24=lossless)
    report.progress(88, "Codificando as faixas")
    files = {name: encode_track([stems[name]], workdir / name, lossless) for name in ("vocals", "drums", "bass", "other")}
    files["playback"] = encode_track([stems["drums"], stems["bass"], stems["other"]], workdir / "playback", lossless)
    report.check()
    if not job.outputs:  # a server from before separated tracks: only the playback
        return Result(files["playback"], files["playback"].name)
    return many(files)


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

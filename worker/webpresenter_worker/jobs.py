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
from datetime import date, datetime, timedelta, timezone
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


def js_runtime_args() -> list[str]:
    return [arg for name in js_runtimes() for arg in ("--js-runtimes", name)]


# Channel pages (the home tab) and the videos of a channel are searched for a day's video.
CHANNEL_PAGE = re.compile(r"^/(@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)/?$")
CHANNEL_SCAN = 40      # the most recent videos of the channel looked at by title
UPLOAD_CHECKS = 8      # and, for the ones whose title does not name the day, their upload dates


def channel_videos_url(url: str) -> str:
    """A channel's home page becomes its videos tab; any other link is searched as it is."""
    parts = urlsplit(url)
    path = parts.path.rstrip("/")
    if CHANNEL_PAGE.match(parts.path):
        path += "/videos"
    return urlunsplit((parts.scheme, parts.netloc, path, parts.query, ""))


def date_in_text(text: str, day: date) -> bool:
    """The day written in a title: 27/11/2026, 27/11/26, 27.11, 27-11-2026… (zeros optional)."""
    # The year is optional, but another year after the day and month is not this day.
    pattern = (rf"(?<!\d)0?{day.day}\s*[/.\-]\s*0?{day.month}"
               rf"(?:\s*[/.\-]\s*(?:{day.year}|{day.year % 100:02d})(?!\d))?(?!\s*[/.\-]\s*\d)(?!\d)")
    return re.search(pattern, text) is not None


def find_channel_video(url: str, day: date, report: Reporter) -> str:
    """
    The link of the channel's video of a day: the most recent videos whose title names the day, then
    the upload dates of the first few. Raises JobFailed when there is none (yet).
    """
    import yt_dlp

    report.progress(3, "Procurando o vídeo do dia no canal")
    options = yt_dlp.parse_options(["--quiet", "--no-warnings", "--skip-download", "--flat-playlist",
                                    "--playlist-end", str(CHANNEL_SCAN), *js_runtime_args()]).ydl_opts
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(channel_videos_url(url), download=False) or {}
            report.check()
            entries = [e for e in info.get("entries") or [] if e] if info.get("_type") == "playlist" else [info]
            entries = [e for e in entries if e.get("id")]
            for entry in entries:
                if date_in_text(entry.get("title") or "", day):
                    return watch_url(entry["id"])
            for entry in entries[:UPLOAD_CHECKS]:
                report.check()
                try:
                    full = ydl.extract_info(watch_url(entry["id"]), download=False) or {}
                except yt_dlp.utils.DownloadError:
                    continue
                if upload_day(full) == day:
                    return watch_url(entry["id"])
    except yt_dlp.utils.DownloadError as e:
        if report.cancelled.is_set():
            raise Cancelled() from e
        first = str(e).replace("ERROR: ", "").splitlines()[0]
        raise JobFailed(f"Não foi possível consultar o canal: {first}") from e
    raise JobFailed(f"Nenhum vídeo de {day:%d/%m/%Y} neste canal ainda")


def upload_day(info: dict) -> date | None:
    """
    The day a video was uploaded, in the local time of the event (WORKER_UTC_OFFSET_HOURS, default -3:
    Brazil keeps no summer time). YouTube's upload_date is in UTC, so an evening upload would be a day late.
    """
    offset = timedelta(hours=float(os.environ.get("WORKER_UTC_OFFSET_HOURS", "-3")))
    if info.get("timestamp"):
        return datetime.fromtimestamp(info["timestamp"], tz=timezone(offset)).date()
    if info.get("upload_date"):
        return datetime.strptime(info["upload_date"], "%Y%m%d").date()
    return None


def watch_url(video_id: str) -> str:
    return f"https://www.youtube.com/watch?v={video_id}"


# Files yt-dlp writes while downloading (or leaves behind): never the result.
PARTIAL_SUFFIXES = {".part", ".ytdl", ".temp", ".json", ".vtt", ".srt"}


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
    # A musical message is kept as its singer provided it: the best quality of the source, in its own
    # container (merging streams only remuxes; audio is not converted).
    as_provided = job.params.get("target") == "MUSICAL_MESSAGE"
    if video and as_provided:
        args += ["-f", "bv*+ba/b", "--merge-output-format", "mp4/mkv"]
    elif video:
        args += ["-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/b[height<=1080]/b",
                 "--merge-output-format", "mp4"]
    elif as_provided:
        args += ["-f", "bestaudio/best"]
    else:
        args += ["-f", "bestaudio[ext=m4a]/bestaudio/best", "-x", "--audio-format", "m4a"]
    options = yt_dlp.parse_options(args).ydl_opts
    options["progress_hooks"] = [hook]

    report.progress(2, "Consultando o link")
    if job.params.get("matchDate"):  # a channel: the video of that day
        url = find_channel_video(url, date.fromisoformat(job.params["matchDate"]), report)
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
    if as_provided:
        # Whatever container the source had: the largest finished file of the download.
        files = sorted((p for p in workdir.iterdir() if p.is_file() and p.suffix not in PARTIAL_SUFFIXES),
                       key=lambda p: p.stat().st_size, reverse=True)
        ext = files[0].suffix.lstrip(".") if files else ""
    else:
        ext = "mp4" if video else "m4a"
        files = sorted(workdir.glob(f"*.{ext}"), key=lambda p: p.stat().st_size, reverse=True)
    if not files:
        raise JobFailed("O download terminou sem gerar um arquivo")
    title = info.get("title") or files[0].stem
    return Result(files[0], safe_filename(title, ext or "bin"), title)


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
                      env: dict[str, str] | None = None, cwd: Path | None = None) -> None:
    """Runs a tool that prints tqdm progress bars, mapping its percentage to start..end."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
                               errors="replace", env=env, cwd=cwd)
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


# ---------------------------------------------------------------------- choir voices

def separate_voices(job: JobRequest, workdir: Path, report: Reporter) -> Result:
    """Choir voices (soprano, alto, tenor, bass, lead, vocal percussion) of a vocals track."""
    from . import voices

    if not job.input_url:
        raise JobFailed("Trabalho sem arquivo de entrada")
    source = workdir / "input"
    report.progress(1, "Baixando a faixa de voz")
    download(job.input_url, source, report, 1, 5, "Baixando a faixa de voz")
    lossless = is_lossless(source)
    wav = workdir / "voices-input.wav"
    convert = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-ac", "2", "-ar", "44100",
                              "-c:a", "pcm_f32le", str(wav)], capture_output=True, text=True)
    if convert.returncode != 0:
        raise JobFailed(f"Não foi possível ler a faixa de voz: {convert.stderr.strip()[:300]}")
    report.progress(6, "Preparando o modelo de naipes")
    try:
        command = voices.separate(wav, workdir / "voices", _device("VOICES_DEVICE"))
    except Exception as e:
        raise JobFailed(f"Não foi possível obter o modelo de naipes: {e}")
    report.check()
    report.progress(10, "Separando os naipes (pode levar alguns minutos)")
    env = {**os.environ, "PYTHONPATH": str(voices.code_dir())}
    run_with_progress(command, report, 10, 88, "Separando os naipes", "A separação dos naipes falhou", env=env,
                      cwd=voices.code_dir())
    import numpy as np
    import soundfile as sf

    mixture, _ = sf.read(wav, dtype="float32", always_2d=True)
    energy = float(np.sum(mixture ** 2))
    report.progress(90, "Codificando os naipes")
    files = {}
    for theirs, ours in voices.OUTPUTS.items():
        track = workdir / "voices" / f"{theirs}.wav"
        if track.exists() and voices.level_db(track, energy) > voices.SILENT_BELOW_DB:
            files[ours] = encode_track([track], workdir / ours, lossless)
    if not files:
        raise JobFailed("Nenhum naipe foi encontrado na faixa")
    report.check()
    return many(files)


def _device(variable: str) -> str:
    """cpu or cuda: the variable when set, otherwise cuda when PyTorch sees a GPU."""
    chosen = os.environ.get(variable)
    if chosen:
        return chosen
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


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
    return lyrics_result(job, words, workdir, report)


def lyrics_result(job: JobRequest, words: list, workdir: Path, report: Reporter) -> Result:
    """
    The lyrics.json of timed words (recognized, or read from a video): aligned to the song's slides
    (params.slides) when it has them, otherwise transcribed into parts and slide cues.
    """
    from . import lyrics

    language = job.params.get("language") or "pt"
    report.progress(94, "Montando os slides")
    known = job.params.get("slides")
    if known:
        cues, matched = lyrics.align(known, words)
        if not cues:
            raise JobFailed("A letra da música não foi encontrada no vídeo" if job.type == "VIDEO_LYRICS"
                            else "A letra da música não foi encontrada na gravação")
        result = {"mode": "aligned", "cues": cues, "confidence": round(matched, 2), "language": language}
    else:
        parsed, cues = lyrics.build_lyrics(words)
        result = {"mode": "transcribed", "lyrics": parsed, "cues": cues, "confidence": None, "language": language}
    target = workdir / "lyrics.json"
    target.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return Result(target, "lyrics.json")


def video_lyrics(job: JobRequest, workdir: Path, report: Reporter) -> Result:
    """
    Lyrics and slide times read from the on-screen text of a video: frames are read (OCR), the lines
    timed by when they first and last show, then handled like a transcription.
    """
    from . import video_text

    if not job.input_url:
        raise JobFailed("Trabalho sem arquivo de entrada")
    source = workdir / "input"
    report.progress(1, "Baixando o vídeo")
    download(job.input_url, source, report, 1, 8, "Baixando o vídeo")
    lines = video_text.read_lines(source, workdir, report, 9, 90)
    report.check()
    words = video_text.timed_words(lines)
    if not words:
        raise JobFailed("Nenhum texto legível encontrado no vídeo")
    return lyrics_result(job, words, workdir, report)


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
            "TRANSCRIBE_LYRICS": transcribe_lyrics, "SEPARATE_VOICES": separate_voices,
            "VIDEO_LYRICS": video_lyrics}

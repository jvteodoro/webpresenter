"""
Compares choir-voice separation models on a real recording (no reference tracks): runs each model
on a vocals track and measures, per separated voice,

- level: its energy relative to the input (a near-silent voice was not found);
- pitch: median and range of the sung notes (pYIN). Soprano > alto > tenor > bass is expected;
  voices whose pitch overlaps another's are probably the same singers split twice;
- leakage: how similar each voice's spectrogram envelope is to the others' (high = the same sound
  in several outputs);
- reconstruction: how well the voices add up to the input (low = parts of the singing were lost);
- with --expected (the voices the recording really has, e.g. soprano,alto for a female duet): the
  share of the energy that went to voices that are not there (lower is better), and whether the
  expected voices come out in pitch order.

Writes report.json and an AAC file per voice (for listening) under --out/<model>/.

Usage (in the GPU worker image with the evaluation extras, see deploy/gpu-worker/eval):
    python evaluate_voices.py --input vocals.m4a --out results \
        --sepacap-repo SepACap --sepacap-checkpoint SepACap.pth --sepacap-config modelMusicSep.yaml \
        --acappellaset-repo ACappellaSet --acappellaset-checkpoint Fixed_6stem_htdemucs_SKnJC.pt
"""
from __future__ import annotations

import argparse
import itertools
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
VOICE_ORDER = ["soprano", "alto", "tenor", "bass", "lead", "other", "vocal_percussion", "finger_snap"]


def load(path: Path, sr: int = SR) -> np.ndarray:
    """Mono float32 at sr, decoded with ffmpeg (any input format)."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def encode(audio: np.ndarray, sr: int, target: Path) -> None:
    peak = float(np.max(np.abs(audio))) or 1.0
    scaled = audio / peak if peak > 1 else audio
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(sr), "-ac", "1", "-i", "-",
                    "-c:a", "aac", "-b:a", "160k", str(target)], input=scaled.astype(np.float32).tobytes(), check=True)


# ---------------------------------------------------------------------- models

def run_sepacap(audio44: np.ndarray, repo: Path, checkpoint: Path, config: Path, device: str) -> dict[str, np.ndarray]:
    """SepACap (SepReformer): 24 kHz mono, 7 fixed outputs; chunked overlap-add for full songs."""
    import torch
    import torchaudio
    import yaml

    sys.path.insert(0, str(repo))
    from src.model import Model  # noqa: E402  (the repository's own code)

    cfg = yaml.safe_load(config.read_text())["config"]
    model = Model(**cfg["model"]).to(device)
    state = torch.load(checkpoint, map_location="cpu")
    state = state.get("model_state", state)
    # Saved from DataParallel: every name starts with "module.". The repository's example loads with
    # strict=False, which silently leaves the model untrained; here every weight must match.
    state = {k.removeprefix("module."): v for k, v in state.items()}
    model.load_state_dict(state, strict=True)
    print("SepACap loaded (all weights)", flush=True)
    model.eval()
    x = torchaudio.functional.resample(torch.from_numpy(audio44), SR, 24000)
    names = ["alto", "bass", "finger_snap", "lead", "soprano", "tenor", "vocal_percussion"]
    chunk, hop = 24000 * 4, 24000 * 2  # the training length (4 s); fits a 4 GB GPU
    window = torch.hann_window(chunk, periodic=False)
    total = x.shape[0]
    length = max(total, chunk)
    length += (hop - (length - chunk) % hop) % hop  # whole chunks
    padded = torch.nn.functional.pad(x, (0, length - total))
    out = torch.zeros(len(names), padded.shape[0])
    weight = torch.zeros(padded.shape[0])
    with torch.inference_mode():
        starts = range(0, padded.shape[0] - chunk + 1, hop)
        began = time.monotonic()
        for n, start in enumerate(starts):
            if n and n % 5 == 0:
                print(f"SepACap {n}/{len(starts)} chunks, {time.monotonic() - began:.0f} s", flush=True)
            piece = padded[start:start + chunk].to(device).unsqueeze(0)
            sources, _ = model(piece)
            for i, s in enumerate(sources[:len(names)]):
                out[i, start:start + chunk] += s.squeeze().float().cpu()[:chunk] * window
            weight[start:start + chunk] += window
    out = out / weight.clamp(min=1e-3)
    result = {}
    for i, name in enumerate(names):
        y = torchaudio.functional.resample(out[i, :total], 24000, SR).numpy()
        result[name] = y[:audio44.shape[0]]
    del model
    torch.cuda.empty_cache()
    return result


def run_acappellaset(input_path: Path, repo: Path, checkpoint: Path, workdir: Path, device: str) -> dict[str, np.ndarray]:
    """ACappellaSet fixed 6-output HTDemucs, through the repository's own inference entry point."""
    wav = workdir / "acappellaset-input.wav"
    sf.write(wav, load(input_path), SR)
    out = workdir / "acappellaset-out"
    command = [sys.executable, "inference.py", "--model", "Fixed_6stem_htdemucs_SKnJC", "--checkpoint",
               str(checkpoint), "--input", str(wav), "--output", str(out), "--device", device]
    subprocess.run(command, cwd=repo, check=True)
    names = {"soprano": "soprano", "alto": "alto", "tenor": "tenor", "bass": "bass", "vp": "vocal_percussion",
             "other": "other"}
    return {ours: load(out / f"{theirs}.wav") for theirs, ours in names.items()}


# ---------------------------------------------------------------------- measurements

def pitch(audio: np.ndarray) -> dict:
    import librosa

    y = librosa.resample(audio, orig_sr=SR, target_sr=16000)
    f0, voiced, _ = librosa.pyin(y, fmin=60, fmax=1100, sr=16000, frame_length=1024, hop_length=320)
    sung = f0[voiced & ~np.isnan(f0)]
    if sung.size < 20:
        return {"voicedShare": round(float(np.mean(voiced)), 3), "median": None}
    low, median, high = np.percentile(sung, [10, 50, 90])
    return {"voicedShare": round(float(np.mean(voiced)), 3), "median": librosa.hz_to_note(median),
            "medianHz": round(float(median), 1), "range": f"{librosa.hz_to_note(low)}–{librosa.hz_to_note(high)}"}


def envelope(audio: np.ndarray) -> np.ndarray:
    import librosa

    spec = np.abs(librosa.stft(librosa.resample(audio, orig_sr=SR, target_sr=16000), n_fft=1024, hop_length=512))
    return np.log1p(spec).ravel()


def measure(mixture: np.ndarray, voices: dict[str, np.ndarray], expected: list[str] | None = None) -> dict:
    energy = float(np.sum(mixture ** 2)) or 1.0
    report = {}
    for name, y in voices.items():
        share = float(np.sum(y ** 2)) / energy
        report[name] = {"levelDb": round(10 * np.log10(share + 1e-12), 1), **(pitch(y) if share > 1e-3 else {})}
    envelopes = {n: envelope(y) for n, y in voices.items() if report[n]["levelDb"] > -30}
    leakage = {}
    for a, b in itertools.combinations(envelopes, 2):
        ea, eb = envelopes[a] - envelopes[a].mean(), envelopes[b] - envelopes[b].mean()
        leakage[f"{a}/{b}"] = round(float(ea @ eb / (np.linalg.norm(ea) * np.linalg.norm(eb) + 1e-9)), 3)
    total = sum(voices.values())
    n = min(total.shape[0], mixture.shape[0])
    error = mixture[:n] - total[:n]
    reconstruction = 10 * np.log10(np.sum(mixture[:n] ** 2) / (np.sum(error ** 2) + 1e-12))
    satb = [v for v in ("soprano", "alto", "tenor", "bass") if not expected or v in expected]
    order = [v for v in satb if report.get(v, {}).get("medianHz")]
    pitches = [report[v]["medianHz"] for v in order]
    extra = {}
    if expected:
        energies = {n: float(np.sum(y ** 2)) for n, y in voices.items()}
        total_energy = sum(energies.values()) or 1.0
        extra["absentVoicesShare"] = round(sum(e for n, e in energies.items() if n not in expected) / total_energy, 3)
        extra["expectedVoicesFound"] = [v for v in expected if report.get(v, {}).get("levelDb", -99) > -20]
    return {
        **extra,
        "voices": report,
        "leakage": leakage,
        "meanLeakage": round(float(np.mean(list(leakage.values()))), 3) if leakage else None,
        "reconstructionDb": round(float(reconstruction), 1),
        "pitchOrderHolds": pitches == sorted(pitches, reverse=True) and len(pitches) == len(satb),
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--device", default="cuda")
    p.add_argument("--sepacap-device", help="SepACap needs more than 4 GB of GPU memory; cpu works (default: --device)")
    p.add_argument("--sepacap-repo", type=Path)
    p.add_argument("--sepacap-checkpoint", type=Path)
    p.add_argument("--sepacap-config", type=Path)
    p.add_argument("--acappellaset-repo", type=Path)
    p.add_argument("--acappellaset-checkpoint", type=Path)
    p.add_argument("--expected", help="voices the recording has, e.g. soprano,alto")
    p.add_argument("--measure-only", action="store_true", help="measure the files already in --out/<model>/")
    args = p.parse_args()
    expected = [v.strip() for v in args.expected.split(",")] if args.expected else None
    args.out.mkdir(parents=True, exist_ok=True)
    mixture = load(args.input)
    results = {"input": args.input.name, "durationS": round(mixture.shape[0] / SR, 1), "expected": expected,
               "models": {}}
    if args.measure_only:
        for folder in sorted(p for p in args.out.iterdir() if p.is_dir() and any(p.glob("*.m4a"))):
            voices = {f.stem: load(f) for f in folder.glob("*.m4a")}
            n = min(len(mixture), *(len(y) for y in voices.values()))
            results["models"][folder.name] = measure(mixture[:n], {k: y[:n] for k, y in voices.items()}, expected)
            print(folder.name, json.dumps(results["models"][folder.name], ensure_ascii=False), flush=True)
        (args.out / "report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
        return

    runs = {}
    # ACappellaSet first: it runs in its own process, which returns all GPU memory when it ends.
    if args.acappellaset_repo:
        runs["acappellaset"] = lambda: run_acappellaset(args.input, args.acappellaset_repo.resolve(),
                                                        args.acappellaset_checkpoint.resolve(), args.out.resolve(),
                                                        args.device)
    if args.sepacap_repo:
        runs["sepacap"] = lambda: run_sepacap(mixture, args.sepacap_repo, args.sepacap_checkpoint,
                                              args.sepacap_config, args.sepacap_device or args.device)
    for name, run in runs.items():
        print(f"== {name}", flush=True)
        started = time.monotonic()
        try:
            voices = run()
        except Exception as e:  # report and go on with the next model
            results["models"][name] = {"error": f"{type(e).__name__}: {e}"}
            print(f"{name} failed: {e}", flush=True)
            continue
        seconds = time.monotonic() - started
        folder = args.out / name
        folder.mkdir(exist_ok=True)
        for voice, y in voices.items():
            encode(y, SR, folder / f"{voice}.m4a")
        results["models"][name] = {"seconds": round(seconds, 1), **measure(mixture, voices, expected)}
        print(json.dumps(results["models"][name], ensure_ascii=False), flush=True)
    (args.out / "report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

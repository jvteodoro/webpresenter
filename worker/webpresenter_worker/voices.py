"""
Choir voices of a vocals track with ACappellaSet's fixed six-output HTDemucs (soprano, alto, tenor,
bass, vocal percussion, and "other": lead or baritone). Chosen over SepACap after evaluating both on
a real recording (worker/tools/evaluate_voices.py): similar separation, and SepACap needs more than
4 GB of GPU memory (about 45 minutes per song on CPU).

The code (MIT) is in the image at ACAPPELLASET_DIR, pinned; the weights (CC BY-NC 4.0: fine for a
church's own use, not for selling) are downloaded on first use into the models volume.
"""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
import requests

MODEL = "Fixed_6stem_htdemucs_SKnJC"
WEIGHTS_URL = ("https://huggingface.co/AcappellaSet/ACappellaSet-Models/resolve/main/"
               "fixed/SKnJC/legacy/Fixed_6stem_htdemucs_SKnJC.pt")
WEIGHTS_SHA256 = "8f87f3a1224de680085fea0c186600edc7cfbb91b8f56f8862da4772565e0cda"
# Model output -> job output name (and the server's track part).
OUTPUTS = {"soprano": "soprano", "alto": "alto", "tenor": "tenor", "bass": "bass_voice",
           "vp": "vocal_percussion", "other": "lead"}
# A voice this much quieter than the input is not in the recording: no track for it.
SILENT_BELOW_DB = -30.0


def code_dir() -> Path:
    return Path(os.environ.get("ACAPPELLASET_DIR", "/opt/acappellaset"))


def weights(report_progress=None) -> Path:
    """The checkpoint, downloaded once (checked against its published hash)."""
    target = Path(os.environ.get("TORCH_HOME", Path.home() / ".cache" / "torch")) / "acappellaset" / f"{MODEL}.pt"
    if target.exists():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(".part")
    digest = hashlib.sha256()
    with requests.get(WEIGHTS_URL, stream=True, timeout=60) as response:
        response.raise_for_status()
        with open(partial, "wb") as out:
            for chunk in response.iter_content(1 << 20):
                digest.update(chunk)
                out.write(chunk)
    if digest.hexdigest() != WEIGHTS_SHA256:
        partial.unlink(missing_ok=True)
        raise RuntimeError("the downloaded ACappellaSet weights do not match their published hash")
    partial.rename(target)
    return target


def separate(source_wav: Path, out_dir: Path, device: str) -> list[str]:
    """Runs the model's own inference entry point; returns its command (for the caller to run)."""
    return [sys.executable, "inference.py", "--model", MODEL, "--checkpoint", str(weights()), "--input",
            str(source_wav), "--output", str(out_dir), "--device", device,
            "--threads", str(max(1, (os.cpu_count() or 2) // 2))]


def level_db(track: Path, reference_energy: float) -> float:
    import soundfile as sf

    audio, _ = sf.read(track, dtype="float32", always_2d=True)
    return 10 * float(np.log10(float(np.sum(audio ** 2)) / (reference_energy or 1.0) + 1e-12))

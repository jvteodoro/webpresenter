"""
Runs Demucs' command line with torchaudio writing through soundfile.

torchaudio picks the sox backend when it is available, and its bundled libsox crashes
(segmentation fault) on some systems when saving WAV files; soundfile is reliable everywhere.
Demucs 4.0.1 does not let the backend be chosen, so torchaudio.save is wrapped here.

Usage: python -m webpresenter_worker.demucs_runner <demucs arguments>
"""
import functools
import sys

import torchaudio


def _soundfile_save(save):
    @functools.wraps(save)
    def wrapper(*args, **kwargs):
        kwargs["backend"] = "soundfile"
        return save(*args, **kwargs)
    return wrapper


def main() -> None:
    torchaudio.save = _soundfile_save(torchaudio.save)
    from demucs.separate import main as separate
    separate(sys.argv[1:])


if __name__ == "__main__":
    main()

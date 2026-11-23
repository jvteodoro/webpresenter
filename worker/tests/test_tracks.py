import subprocess
import tempfile
import unittest
from pathlib import Path

from webpresenter_worker import jobs


def tone(path: Path, frequency: int, codec: list[str]) -> Path:
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency={frequency}:duration=1",
                    "-ac", "2", *codec, str(path)], check=True)
    return path


def probe(path: Path, entry: str) -> str:
    return subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", entry,
                           "-of", "default=nw=1:nk=1", str(path)], capture_output=True, text=True).stdout.strip()


class TracksTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def test_lossless_sources_are_recognized(self):
        self.assertTrue(jobs.is_lossless(tone(self.dir / "a.flac", 440, ["-c:a", "flac"])))
        self.assertTrue(jobs.is_lossless(tone(self.dir / "a.wav", 440, ["-c:a", "pcm_s16le"])))
        self.assertFalse(jobs.is_lossless(tone(self.dir / "a.m4a", 440, ["-c:a", "aac"])))
        self.assertFalse(jobs.is_lossless(self.dir / "missing"))

    def test_tracks_keep_lossless_or_are_compressed(self):
        wav = tone(self.dir / "v.wav", 440, ["-c:a", "pcm_s16le"])
        flac = jobs.encode_track([wav], self.dir / "vocals", lossless=True)
        self.assertEqual(".flac", flac.suffix)
        self.assertEqual("flac", probe(flac, "stream=codec_name"))
        m4a = jobs.encode_track([wav], self.dir / "vocals", lossless=False)
        self.assertEqual("aac", probe(m4a, "stream=codec_name"))

    def test_the_playback_is_the_sum_of_the_instruments(self):
        parts = [tone(self.dir / f"{n}.wav", f, ["-c:a", "pcm_s16le"]) for n, f in (("d", 200), ("b", 300), ("o", 500))]
        mixed = jobs.encode_track(parts, self.dir / "playback", lossless=True)
        self.assertAlmostEqual(1.0, float(probe(mixed, "format=duration") or probe(mixed, "stream=duration") or 1), 1)
        # Not normalized: three tones at full level add up (louder than one).
        one = jobs.encode_track(parts[:1], self.dir / "one", lossless=True)
        self.assertGreater(self.peak(mixed), self.peak(one))

    @staticmethod
    def peak(path: Path) -> float:
        out = subprocess.run(["ffmpeg", "-v", "info", "-i", str(path), "-af", "volumedetect", "-f", "null", "-"],
                             capture_output=True, text=True).stderr
        return float(out.split("mean_volume:")[1].split("dB")[0])


if __name__ == "__main__":
    unittest.main()

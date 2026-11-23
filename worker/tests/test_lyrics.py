import unittest

from webpresenter_worker import lyrics
from webpresenter_worker.lyrics import Word


def sung(text: str, start: float, step: float = 0.4) -> list[Word]:
    """Words of a sung line, evenly spaced."""
    return [Word(w, start + i * step, start + i * step + step * 0.8) for i, w in enumerate(text.split())]


VERSE1 = ["Vinde a mim os cansados", "Eu vos aliviarei"]
CHORUS = ["Tomai sobre vós o meu jugo", "E aprendei de mim"]
VERSE2 = ["Sou manso e humilde", "De coração"]


def song_words() -> list[Word]:
    """Verse, chorus, verse, chorus — with long pauses between blocks."""
    words, t = [], 10.0
    for block in (VERSE1, CHORUS, VERSE2, CHORUS):
        for line in block:
            words += sung(line, t)
            t = words[-1].end + 1.2
        t += 8.0
    return words


class BuildLyricsTest(unittest.TestCase):
    def test_repeated_block_becomes_the_chorus_part(self):
        parsed, cues = lyrics.build_lyrics(song_words())
        labels = {p["id"]: p["label"] for p in parsed["parts"]}
        self.assertEqual(["v1", "i", "c", "i", "v2", "i", "c"], parsed["order"])
        self.assertEqual("Coro", labels["c"])
        chorus = next(p for p in parsed["parts"] if p["id"] == "c")
        self.assertEqual([["Tomai sobre vós o meu jugo", "E aprendei de mim"]], [s["lines"] for s in chorus["slides"]])

    def test_one_cue_per_slide_of_the_sequence_moving_forward(self):
        parsed, cues = lyrics.build_lyrics(song_words())
        slides = sum(len(next(p for p in parsed["parts"] if p["id"] == pid)["slides"]) for pid in parsed["order"])
        self.assertEqual(slides, len(cues))
        self.assertEqual(list(range(slides)), [c["slide"] for c in cues])
        times = [c["startMs"] for c in cues]
        self.assertEqual(sorted(times), times)
        self.assertEqual(10_000, times[0])  # the first line starts the first slide (intro before it)


class ChorusVariantsTest(unittest.TestCase):
    def test_a_chorus_heard_differently_each_time_is_one_part_with_the_majority_wording(self):
        def phrase(text, start):
            ws = sung(text, start)
            ws[0].phrase_start = True
            return ws

        words, t = [], 10.0
        verses = [["Quantas vezes te magoei", "Feri teu coração"], ["Hoje venho me render", "Quero ser fiel a ti"],
                  ["Mesmo quando eu me afastar", "Ajuda-me a ouvir tua voz"]]
        heard_chorus = ["Fim de a mim eu nunca mudei", "Vinde a mim eu nunca mudei", "Vinde a mim eu nunca mudei"]
        for verse, first in zip(verses, heard_chorus):
            for line in verse:
                words += phrase(line, t); t = words[-1].end + 1.0
            t += 6.0
            for line in [first, "Como um pai estou a te esperar"]:
                words += phrase(line, t); t = words[-1].end + 1.0
            t += 6.0
        parsed, cues = lyrics.build_lyrics(words)
        chorus = [p for p in parsed["parts"] if p["kind"] == "CHORUS"]
        self.assertEqual(1, len(chorus))
        self.assertEqual("Vinde a mim eu nunca mudei", chorus[0]["slides"][0]["lines"][0])
        self.assertEqual(3, parsed["order"].count(chorus[0]["id"]))


class AlignTest(unittest.TestCase):
    def test_known_lyrics_find_their_times_despite_recognition_errors(self):
        heard = sung("vinde a mim os cansado", 12.0) + sung("eu vos aliviarei", 15.0) \
            + sung("tomai sobre vos o meu jugo", 22.0) + sung("e aprende de mim", 26.0)
        slides = ["Vinde a mim os cansados\nEu vos aliviarei", "Tomai sobre vós o meu jugo\nE aprendei de mim"]
        cues, matched = lyrics.align(slides, heard)
        self.assertEqual([{"slide": 0, "startMs": 12_000}, {"slide": 1, "startMs": 22_000}], cues)
        self.assertGreater(matched, 0.7)

    def test_a_slide_not_sung_gets_no_cue(self):
        heard = sung("vinde a mim os cansados eu vos aliviarei", 5.0)
        cues, _ = lyrics.align(["Vinde a mim os cansados", "Uma estrofe que não foi cantada"], heard)
        self.assertEqual([0], [c["slide"] for c in cues])


if __name__ == "__main__":
    unittest.main()

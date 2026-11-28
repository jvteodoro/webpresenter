import unittest
from datetime import date, datetime, timezone

from webpresenter_worker import jobs, video_text


def frame(t: float, *lines: str) -> tuple[float, list[str]]:
    return (t, list(lines))


class TsvLinesTest(unittest.TestCase):
    HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext"

    def test_words_are_grouped_into_lines_top_to_bottom_and_low_confidence_words_are_left_out(self):
        tsv = "\n".join([
            self.HEADER,
            "1\t1\t0\t0\t0\t0\t0\t0\t100\t100\t-1\t",
            "5\t1\t1\t1\t1\t1\t10\t300\t40\t20\t90\tsegunda",
            "5\t1\t1\t1\t1\t2\t60\t300\t40\t20\t88\tlinha",
            "5\t1\t1\t1\t2\t1\t10\t100\t40\t20\t95\tprimeira",
            "5\t1\t1\t1\t2\t2\t60\t100\t40\t20\t20\tlixo",
            "5\t1\t1\t1\t2\t3\t110\t100\t40\t20\t92\tlinha",
        ])
        self.assertEqual(video_text.tsv_lines(tsv), ["primeira linha", "segunda linha"])

    def test_a_row_that_is_not_a_word_is_ignored(self):
        self.assertEqual(video_text.tsv_lines(self.HEADER + "\n4\t1\t1\t1\t1\t0\t0\t0\t9\t9\t-1\t"), [])


class TimingTest(unittest.TestCase):

    def test_a_line_is_timed_from_its_first_frame_to_its_last(self):
        frames = [frame(0.0), frame(0.5, "primeira linha de teste"), frame(1.0, "primeira linha de teste"),
                  frame(1.5, "primeira linha de teste"), frame(2.0)]
        (track,) = video_text.tracks(frames)
        self.assertEqual((track.first, track.last, track.seen), (0.5, 1.5, 3))

    def test_slightly_different_readings_of_one_line_are_one_line(self):
        frames = [frame(0.5, "primeira linha de teste"), frame(1.0, "primeira linha de tste"),
                  frame(1.5, "primeira linha de teste")]
        tracks = video_text.tracks(frames)
        self.assertEqual(len(tracks), 1)
        self.assertEqual(tracks[0].text, "primeira linha de teste")

    def test_two_different_lines_that_begin_alike_are_two_lines(self):
        frames = [frame(0.0, "primeira linha de teste um"), frame(0.5, "primeira linha de teste um"),
                  frame(1.0, "primeira linha de teste dois"), frame(1.5, "primeira linha de teste dois")]
        tracks = video_text.tracks(frames)
        self.assertEqual([t.text for t in tracks], ["primeira linha de teste um", "primeira linha de teste dois"])

    def test_a_line_that_leaves_and_comes_back_later_is_a_new_line(self):
        frames = [frame(0.0, "primeira linha de teste"), frame(0.5, "primeira linha de teste"),
                  frame(3.0), frame(3.5, "primeira linha de teste"), frame(4.0, "primeira linha de teste")]
        tracks = video_text.tracks(frames)
        self.assertEqual([(t.first, t.last) for t in tracks], [(0.0, 0.5), (3.5, 4.0)])

    def test_words_start_with_their_line_and_the_first_word_starts_a_phrase(self):
        frames = [frame(1.0, "primeira linha de teste", "segunda linha de teste"),
                  frame(2.0, "primeira linha de teste", "segunda linha de teste")]
        words = video_text.timed_words(frames)
        self.assertEqual([w.text for w in words][:4], ["primeira", "linha", "de", "teste"])
        self.assertTrue(all(w.start == 1.0 for w in words[:4]))
        self.assertEqual([w.phrase_start for w in words][:5], [True, False, False, False, True])

    def test_a_reading_seen_in_one_frame_only_is_noise(self):
        frames = [frame(0.0, "uma leitura falsa qualquer"), frame(0.5), frame(1.0)]
        self.assertEqual(video_text.timed_words(frames), [])

    def test_a_short_reading_is_noise(self):
        frames = [frame(0.0, "ab"), frame(0.5, "ab")]
        self.assertEqual(video_text.timed_words(frames), [])

    def test_a_line_read_twice_in_one_frame_counts_once(self):
        frames = [frame(0.0, "primeira linha de teste", "primeira linha de teste"), frame(0.5)]
        (track,) = video_text.tracks(frames)
        self.assertEqual(track.seen, 1)


class ChannelTest(unittest.TestCase):

    def test_the_day_written_in_a_title_is_found_with_or_without_zeros_and_year(self):
        day = date(2026, 11, 27)
        self.assertTrue(jobs.date_in_text("Programa 27/11/2026 - tema", day))
        self.assertTrue(jobs.date_in_text("Programa 27.11.26", day))
        self.assertTrue(jobs.date_in_text("Programa 27-11", day))
        self.assertTrue(jobs.date_in_text("Programa 27/11/2026", day))

    def test_other_days_and_numbers_that_contain_the_day_are_not_the_day(self):
        day = date(2026, 11, 27)
        self.assertFalse(jobs.date_in_text("Programa 28/11/2026", day))
        self.assertFalse(jobs.date_in_text("Programa 127/11/2026", day))
        self.assertFalse(jobs.date_in_text("Programa 27/11/2027", day))
        self.assertFalse(jobs.date_in_text("Programa 27/12/2026", day))
        self.assertFalse(jobs.date_in_text("Programa sem data", day))

    def test_a_single_digit_day_matches_with_or_without_zero(self):
        self.assertTrue(jobs.date_in_text("Edição 01/02/2026", date(2026, 2, 1)))
        self.assertTrue(jobs.date_in_text("Edição 1/2", date(2026, 2, 1)))
        self.assertFalse(jobs.date_in_text("Edição 11/12/2026", date(2026, 1, 12)))

    def test_an_evening_upload_in_brazil_is_the_day_of_the_event_not_the_next_utc_day(self):
        # 2026-11-27 22:30 in Brasília (UTC-3) is 2026-11-28 01:30 UTC.
        timestamp = datetime(2026, 11, 28, 1, 30, tzinfo=timezone.utc).timestamp()
        self.assertEqual(jobs.upload_day({"timestamp": timestamp, "upload_date": "20261128"}), date(2026, 11, 27))
        self.assertEqual(jobs.upload_day({"upload_date": "20261127"}), date(2026, 11, 27))
        self.assertIsNone(jobs.upload_day({}))

    def test_a_channel_home_page_is_searched_in_its_videos_tab(self):
        self.assertEqual(jobs.channel_videos_url("https://www.youtube.com/@Canal/"),
                         "https://www.youtube.com/@Canal/videos")
        self.assertEqual(jobs.channel_videos_url("https://www.youtube.com/@Canal"),
                         "https://www.youtube.com/@Canal/videos")
        self.assertEqual(jobs.channel_videos_url("https://www.youtube.com/channel/UC123/videos"),
                         "https://www.youtube.com/channel/UC123/videos")
        self.assertEqual(jobs.channel_videos_url("https://www.youtube.com/watch?v=abc"),
                         "https://www.youtube.com/watch?v=abc")


if __name__ == "__main__":
    unittest.main()

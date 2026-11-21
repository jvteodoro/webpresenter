package webpresenter.server.songs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import webpresenter.server.songs.Lyrics.Part;
import webpresenter.server.songs.Lyrics.PartKind;
import webpresenter.server.songs.Lyrics.Slide;

class LyricsTests {

    static final Lyrics SONG = new Lyrics(List.of(
            new Part("v1", PartKind.VERSE, "Estrofe 1", List.of(new Slide(List.of("Linha 1", "Linha 2"), null),
                    new Slide(List.of("Linha 3"), null))),
            new Part("c", PartKind.CHORUS, "Coro", List.of(new Slide(List.of("Coro"), "Todos:"))),
            new Part("i", PartKind.INSTRUMENTAL, "Instrumental", List.of(new Slide(List.of(), null)))),
            List.of("v1", "c", "i", "c"));

    @Test
    void expandsTheSingingOrderIntoTheSlideSequence() {
        var sequence = SONG.validate().slideSequence();

        assertThat(sequence).extracting(Lyrics.SequenceSlide::partId).containsExactly("v1", "v1", "c", "i", "c");
        assertThat(sequence).extracting(Lyrics.SequenceSlide::index).containsExactly(0, 1, 2, 3, 4);
        assertThat(sequence.get(2).cue()).isEqualTo("Todos:");
        assertThat(sequence.get(1).slideInPart()).isEqualTo(1);
    }

    @Test
    void rejectsBrokenStructures() {
        assertThatThrownBy(() -> new Lyrics(SONG.parts(), List.of("v1", "x")).validate())
                .hasMessageContaining("unknown part x");
        assertThatThrownBy(() -> new Lyrics(List.of(SONG.parts().get(0), SONG.parts().get(0)), List.of()).validate())
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new Lyrics(List.of(new Part("e", PartKind.VERSE, "", List.of())), List.of()).validate())
                .hasMessageContaining("no slides");
    }

    @Test
    void timingMustFollowTheSequence() {
        new Timing(1, Source.MANUAL, true, List.of(new Timing.Cue(0, 0), new Timing.Cue(2, 9000))).validate(5);

        assertThatThrownBy(() -> new Timing(1, Source.MANUAL, true, List.of(new Timing.Cue(5, 0))).validate(5))
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> new Timing(1, Source.MANUAL, true,
                List.of(new Timing.Cue(1, 5000), new Timing.Cue(2, 4000))).validate(5))
                .hasMessageContaining("time order");
    }
}

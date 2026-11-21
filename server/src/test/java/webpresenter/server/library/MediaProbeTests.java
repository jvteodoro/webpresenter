package webpresenter.server.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MediaProbeTests {

    @TempDir
    static Path dir;

    private final MediaProbe probe = new MediaProbe(new LibraryProperties(null, null), JsonMapper.builder().build());

    @BeforeAll
    static void requireFfmpeg() {
        TestMedia.assumeFfmpeg();
    }

    @Test
    void detectsVideoWithDurationAndSize() {
        var result = probe.probe(TestMedia.video(dir)).orElseThrow();

        assertThat(result.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(result.durationMs()).isBetween(1900L, 2200L);
        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(180);
    }

    @Test
    void detectsAudio() {
        var result = probe.probe(TestMedia.audio(dir)).orElseThrow();

        assertThat(result.kind()).isEqualTo(MediaKind.AUDIO);
        assertThat(result.durationMs()).isBetween(2900L, 3200L);
        assertThat(result.width()).isNull();
    }

    @Test
    void detectsImageWithoutDuration() {
        var result = probe.probe(TestMedia.image(dir)).orElseThrow();

        assertThat(result.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(result.durationMs()).isNull();
        assertThat(result.width()).isEqualTo(640);
    }

    @Test
    void rejectsNonMediaFiles() throws Exception {
        Path text = Files.writeString(dir.resolve("notes.txt"), "not a media file");

        assertThat(probe.probe(text)).isEmpty();
    }
}

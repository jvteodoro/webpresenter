package webpresenter.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/** Generates tiny media files with ffmpeg; tests using it are skipped when ffmpeg is missing. */
public final class TestMedia {

    private TestMedia() {}

    public static void assumeFfmpeg() {
        Assumptions.assumeTrue(runs("ffmpeg", "-version") && runs("ffprobe", "-version"),
                "ffmpeg/ffprobe not installed");
    }

    public static Path video(Path dir) {
        return ffmpeg(dir.resolve("clip.mp4"), "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest");
    }

    public static Path audio(Path dir) {
        return ffmpeg(dir.resolve("tone.m4a"), "-f", "lavfi", "-i", "sine=frequency=330", "-t", "3", "-c:a", "aac");
    }

    /** A tone of the given frequency: distinct content per frequency (the library de-duplicates). */
    public static Path tone(Path dir, int frequency) {
        return ffmpeg(dir.resolve("tone-" + frequency + ".m4a"), "-f", "lavfi", "-i", "sine=frequency=" + frequency,
                "-t", "2", "-c:a", "aac");
    }

    public static Path image(Path dir) {
        return ffmpeg(dir.resolve("frame.png"), "-f", "lavfi", "-i", "testsrc2=size=640x360", "-frames:v", "1");
    }

    private static Path ffmpeg(Path output, String... args) {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-loglevel", "error", "-y"));
        command.addAll(List.of(args));
        command.add(output.toString());
        if (!runs(command.toArray(String[]::new))) {
            throw new IllegalStateException("ffmpeg failed to create " + output);
        }
        return output;
    }

    private static boolean runs(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

package webpresenter.server.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Inspects media files with ffprobe: kind, duration and dimensions. */
@Component
class MediaProbe {

    /** ffprobe formats that represent a still image rather than a video. */
    private static final Set<String> IMAGE_FORMATS = Set.of(
            "image2", "png_pipe", "jpeg_pipe", "webp_pipe", "gif", "bmp_pipe", "tiff_pipe", "svg_pipe");

    private final String ffprobe;
    private final JsonMapper json;

    MediaProbe(LibraryProperties properties, JsonMapper json) {
        this.ffprobe = properties.ffprobePath();
        this.json = json;
    }

    /**
     * @return the probe result, or empty if the file is not a playable video, audio or image
     * @throws MediaProbeException if ffprobe cannot be run
     */
    Optional<ProbeResult> probe(Path file) {
        String output = run(file);
        if (output == null) {
            return Optional.empty();
        }
        JsonNode root = json.readTree(output);
        String formatName = root.path("format").path("format_name").asString("");
        Long durationMs = parseDurationMs(root.path("format").path("duration").asString(null));

        JsonNode video = null;
        boolean hasAudio = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asString("");
            boolean coverArt = stream.path("disposition").path("attached_pic").asInt(0) == 1;
            if (type.equals("video") && !coverArt && video == null) {
                video = stream;
            } else if (type.equals("audio")) {
                hasAudio = true;
            }
        }

        Integer width = video == null ? null : video.path("width").asInt(0);
        Integer height = video == null ? null : video.path("height").asInt(0);
        if (video != null && isImage(formatName)) {
            return Optional.of(new ProbeResult(MediaKind.IMAGE, null, width, height));
        }
        if (video != null) {
            return Optional.of(new ProbeResult(MediaKind.VIDEO, durationMs, width, height));
        }
        if (hasAudio) {
            return Optional.of(new ProbeResult(MediaKind.AUDIO, durationMs, null, null));
        }
        return Optional.empty();
    }

    private static boolean isImage(String formatName) {
        for (String name : formatName.split(",")) {
            if (IMAGE_FORMATS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static Long parseDurationMs(String seconds) {
        if (seconds == null || seconds.isBlank() || seconds.equals("N/A")) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return ffprobe's JSON output, or null when ffprobe rejects the file */
    private String run(Path file) {
        List<String> command = new ArrayList<>(List.of(
                ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                file.toAbsolutePath().toString()));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] stdout = process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new MediaProbeException("ffprobe timed out on " + file.getFileName());
            }
            return process.exitValue() == 0 ? new String(stdout, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new MediaProbeException("cannot run ffprobe ('" + ffprobe + "'): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaProbeException("interrupted while probing " + file.getFileName(), e);
        }
    }

    record ProbeResult(MediaKind kind, Long durationMs, Integer width, Integer height) {}

    static class MediaProbeException extends RuntimeException {
        MediaProbeException(String message) {
            super(message);
        }

        MediaProbeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

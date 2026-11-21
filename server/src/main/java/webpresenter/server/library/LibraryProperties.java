package webpresenter.server.library;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ffprobePath ffprobe executable used to inspect uploads
 * @param downloadUrlValidity how long presigned download links stay valid
 */
@ConfigurationProperties("webpresenter.library")
record LibraryProperties(String ffprobePath, Duration downloadUrlValidity) {

    LibraryProperties {
        if (ffprobePath == null || ffprobePath.isBlank()) {
            ffprobePath = "ffprobe";
        }
        if (downloadUrlValidity == null) {
            downloadUrlValidity = Duration.ofHours(6);
        }
    }
}

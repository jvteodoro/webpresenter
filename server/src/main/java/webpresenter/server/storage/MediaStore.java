package webpresenter.server.storage;

import java.nio.file.Path;
import java.time.Duration;

/** Object storage for media files (S3-compatible). Keys are opaque paths chosen by the caller. */
public interface MediaStore {

    /** Stores a local file under the given key, replacing any existing object. */
    void put(String key, Path file, String contentType);

    /** Creates a time-limited URL that browsers can download from directly. */
    PresignedUrl presignedDownload(String key, Duration validity);

    /**
     * Like {@link #presignedDownload} but for MediaPlayers, which may use another address (for
     * example plain HTTP on the LAN while browsers go through HTTPS).
     */
    PresignedUrl presignedPlayerDownload(String key, Duration validity);

    /** Removes the object; removing a missing key is not an error. */
    void delete(String key);
}

package webpresenter.server.library;

import java.nio.file.Path;

/** Adds files produced by the server itself (processing results) to the library. */
public interface MediaImporter {

    /**
     * Registers a local file exactly like an upload (inspection, storage, metadata). The file is
     * copied; the caller keeps and removes it.
     *
     * @throws RuntimeException if the file is not audio, video or an image (as an upload would be refused)
     */
    MediaFile importFile(Path file, String filename, String title, MediaPurpose purpose, String createdBy);
}

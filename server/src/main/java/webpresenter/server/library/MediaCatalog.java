package webpresenter.server.library;

import java.util.Optional;
import java.util.UUID;
import webpresenter.server.storage.PresignedUrl;

/** Read access to the library for other modules. */
public interface MediaCatalog {

    Optional<MediaFile> find(UUID id);

    /** @throws java.util.NoSuchElementException if the media does not exist */
    PresignedUrl downloadUrl(UUID id);

    /** Download link for MediaPlayers (their address for the media store may differ from browsers'). */
    PresignedUrl playerDownloadUrl(UUID id);
}

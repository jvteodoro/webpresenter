package webpresenter.server.library;

import java.util.UUID;

/** What other modules need to know about a library file. */
public record MediaFile(UUID id, String title, MediaKind kind, String contentType, long sizeBytes, String sha256) {}

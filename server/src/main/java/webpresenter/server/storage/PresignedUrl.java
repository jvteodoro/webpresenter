package webpresenter.server.storage;

import java.net.URI;
import java.time.Instant;

/** A direct download URL for a stored object and the instant it stops working. */
public record PresignedUrl(URI url, Instant expiresAt) {}

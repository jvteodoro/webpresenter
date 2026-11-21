package webpresenter.server.library;

import java.util.UUID;

/** Published inside the deleting transaction, so other modules can drop their references. */
public record MediaAssetDeleted(UUID id) {}

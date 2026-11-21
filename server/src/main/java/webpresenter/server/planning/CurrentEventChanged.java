package webpresenter.server.planning;

import java.util.UUID;

/**
 * The current event changed: another event was chosen, it was cleared, or its items changed.
 * Published inside the planning transaction; listeners should react after commit.
 *
 * @param eventId the current event, or null if there is none
 */
public record CurrentEventChanged(UUID eventId) {}

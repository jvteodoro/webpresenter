package webpresenter.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * Wrapper for every message on the wire: {@code {"type", "id", "ts", "payload"}}.
 *
 * @param type message type
 * @param id unique message id, used for correlation and logging
 * @param ts instant the message was created by the sender
 * @param payload type-specific content
 */
public record Envelope(MessageType type, String id, Instant ts, Message payload) {

    public Envelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(payload, "payload");
        if (!type.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException(type + " cannot carry " + payload.getClass().getSimpleName());
        }
    }
}

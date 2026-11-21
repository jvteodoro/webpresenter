package webpresenter.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * Wrapper for every message exchanged between the central server and a MediaPlayer.
 *
 * @param type message type, for example {@code HELLO} or {@code PLAY}
 * @param id unique message id, used for correlation and logging
 * @param ts instant the message was created by the sender
 * @param payload type-specific content
 */
public record Envelope<T>(String type, String id, Instant ts, T payload) {

    public Envelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ts, "ts");
    }
}

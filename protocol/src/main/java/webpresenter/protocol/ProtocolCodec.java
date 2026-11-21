package webpresenter.protocol;

import java.time.Clock;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Converts messages to and from their JSON wire format. Thread-safe. */
public final class ProtocolCodec {

    private final JsonMapper json = JsonMapper.builder()
            // Newer peers may add fields; older peers must keep working.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final Clock clock;

    public ProtocolCodec() {
        this(Clock.systemUTC());
    }

    public ProtocolCodec(Clock clock) {
        this.clock = clock;
    }

    /** Wraps a message in a new envelope (fresh id, current time) and serializes it. */
    public String encode(Message message) {
        return encode(new Envelope(MessageType.of(message), UUID.randomUUID().toString(), clock.instant(), message));
    }

    public String encode(Envelope envelope) {
        ObjectNode node = json.createObjectNode();
        node.put("type", envelope.type().name());
        node.put("id", envelope.id());
        node.put("ts", envelope.ts().toString());
        node.set("payload", json.valueToTree(envelope.payload()));
        return json.writeValueAsString(node);
    }

    /** @throws ProtocolException if the text is not a valid message */
    public Envelope decode(String text) {
        try {
            JsonNode node = json.readTree(text);
            MessageType type = MessageType.valueOf(required(node, "type"));
            Message payload = json.treeToValue(node.path("payload"), type.payloadType());
            if (payload == null) {
                throw new ProtocolException("missing payload for " + type);
            }
            return new Envelope(type, required(node, "id"), java.time.Instant.parse(required(node, "ts")), payload);
        } catch (JacksonException | IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new ProtocolException("invalid message: " + e.getMessage(), e);
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new ProtocolException("missing field '" + field + "'");
        }
        return value.asString();
    }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package webpresenter.server.devices;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import webpresenter.protocol.Envelope;
import webpresenter.protocol.Message;
import webpresenter.protocol.ProtocolCodec;

/**
 * WebSocket endpoint for MediaPlayers. Authentication happens in the protocol: the first message
 * must be {@code HELLO}, carrying the device token once the device has been approved.
 */
@Component
class PlayerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PlayerWebSocketHandler.class);
    private static final String DEVICE_ID = "deviceId";

    private final ProtocolCodec codec;
    private final DeviceService devices;
    private final PlayerSessions sessions;

    PlayerWebSocketHandler(ProtocolCodec codec, DeviceService devices, PlayerSessions sessions) {
        this.codec = codec;
        this.devices = devices;
        this.sessions = sessions;
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage text) {
        WebSocketSession session = (WebSocketSession) rawSession.getAttributes()
                .computeIfAbsent("concurrent", k -> PlayerSessions.concurrent(rawSession));
        Envelope envelope;
        try {
            envelope = codec.decode(text.getPayload());
        } catch (ProtocolCodec.ProtocolException e) {
            sessions.send(session, new Message.Error("INVALID_MESSAGE", e.getMessage()));
            return;
        }
        UUID deviceId = (UUID) session.getAttributes().get(DEVICE_ID);
        Message message = envelope.payload();

        if (deviceId == null) {
            if (message instanceof Message.Hello hello) {
                Optional<UUID> accepted = devices.hello(session, hello);
                accepted.ifPresent(id -> session.getAttributes().put(DEVICE_ID, id));
            } else {
                sessions.send(session, new Message.Error("HELLO_REQUIRED", "the first message must be HELLO"));
                PlayerSessions.close(session, CloseStatus.POLICY_VIOLATION.withReason("HELLO_REQUIRED"));
            }
            return;
        }
        switch (message) {
            case Message.CacheStatus status -> devices.cacheStatus(deviceId, status);
            case Message.PlaybackState state -> devices.playback(deviceId, state);
            case Message.Error error -> log.warn("player {} reported {}: {}", deviceId, error.code(), error.message());
            default -> sessions.send(session, new Message.Error("UNEXPECTED_MESSAGE",
                    envelope.type() + " is not accepted from players"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        UUID deviceId = (UUID) rawSession.getAttributes().get(DEVICE_ID);
        if (deviceId != null) {
            WebSocketSession session = (WebSocketSession) rawSession.getAttributes().getOrDefault("concurrent", rawSession);
            devices.disconnected(deviceId, session);
        }
    }
}

package webpresenter.server.devices;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import webpresenter.protocol.Message;
import webpresenter.protocol.ProtocolCodec;
import webpresenter.protocol.ProtocolVersion;

/** Open player connections, by device id, plus the last cache status each player reported. */
@Component
class PlayerSessions {

    private static final Logger log = LoggerFactory.getLogger(PlayerSessions.class);

    private final ProtocolCodec codec;
    private final Map<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Message.CacheStatus> cacheStatus = new ConcurrentHashMap<>();

    PlayerSessions(ProtocolCodec codec) {
        this.codec = codec;
    }

    /** Wraps a raw session so it can be written from several threads. */
    static WebSocketSession concurrent(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024);
    }

    /** Registers the session for the device, closing any previous connection of the same device. */
    void register(UUID deviceId, WebSocketSession session) {
        WebSocketSession previous = sessions.put(deviceId, session);
        if (previous != null && !previous.getId().equals(session.getId())) {
            log.warn("device {} connected again; closing its previous connection", deviceId);
            close(previous, new CloseStatus(ProtocolVersion.CLOSE_REPLACED, "replaced by a newer connection"));
        }
    }

    /** Removes the mapping only if it still points at this session. */
    void unregister(UUID deviceId, WebSocketSession session) {
        if (sessions.remove(deviceId, session)) {
            cacheStatus.remove(deviceId);
        }
    }

    boolean isOnline(UUID deviceId) {
        WebSocketSession s = sessions.get(deviceId);
        return s != null && s.isOpen();
    }

    void updateCacheStatus(UUID deviceId, Message.CacheStatus status) {
        cacheStatus.put(deviceId, status);
    }

    Optional<Message.CacheStatus> cacheStatus(UUID deviceId) {
        return Optional.ofNullable(cacheStatus.get(deviceId));
    }

    /** Sends to the device if it is connected; returns whether it was. */
    boolean send(UUID deviceId, Message message) {
        WebSocketSession session = sessions.get(deviceId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        send(session, message);
        return true;
    }

    /**
     * Sends a message. Inside a transaction the message is held until commit, so a player never
     * acts on state (a new device, a token) that could still be rolled back or is not yet visible
     * to its next request.
     */
    void send(WebSocketSession session, Message message) {
        afterCommit(() -> sendNow(session, message));
    }

    private void sendNow(WebSocketSession session, Message message) {
        try {
            session.sendMessage(new TextMessage(codec.encode(message)));
        } catch (IOException e) {
            log.warn("failed to send {} to session {}: {}", message.getClass().getSimpleName(), session.getId(),
                    e.getMessage());
        }
    }

    void disconnect(UUID deviceId, CloseStatus status) {
        WebSocketSession session = sessions.remove(deviceId);
        cacheStatus.remove(deviceId);
        if (session != null) {
            close(session, status);
        }
    }

    /** Closes after the current transaction commits (immediately when there is none). */
    static void close(WebSocketSession session, CloseStatus status) {
        afterCommit(() -> closeNow(session, status));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static void closeNow(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("closing session {}: {}", session.getId(), e.getMessage());
        }
    }
}

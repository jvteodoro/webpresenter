package webpresenter.server.devices;

import java.util.List;
import java.util.UUID;
import webpresenter.protocol.Message;

/** Approved MediaPlayers, for modules that drive them (live presentation). */
public interface Players {

    List<PlayerSummary> approved();

    /** Sends to every connected, approved player (after the current transaction commits, if any). */
    void broadcast(Message message);

    /** Sends to one player if it is connected (after the current transaction commits, if any). */
    void send(UUID deviceId, Message message);

    record PlayerSummary(UUID id, String name, boolean online, Message.CacheStatus cacheStatus) {}
}

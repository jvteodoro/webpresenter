package webpresenter.server.devices;

import java.util.UUID;
import webpresenter.protocol.Message;

/** Application events published by the devices module. */
public final class PlayerEvents {

    private PlayerEvents() {}

    /**
     * An approved player connected (or was just approved) and has been sent its configuration and
     * plan. Published synchronously inside that transaction, so messages a listener sends to the
     * player are delivered after those.
     */
    public record PlayerReady(UUID deviceId) {}

    /** A player's connection or cache status changed. */
    public record PlayerStatusChanged(UUID deviceId) {}

    /** A player reported what it is presenting. */
    public record PlaybackReported(UUID deviceId, Message.PlaybackState state) {}
}

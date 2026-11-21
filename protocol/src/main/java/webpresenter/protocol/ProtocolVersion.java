package webpresenter.protocol;

/** Version of the server ↔ player protocol, sent by the player in {@link Message.Hello}. */
public final class ProtocolVersion {

    /** Incremented on every incompatible change to the message contract. */
    public static final int CURRENT = 1;

    /** WebSocket path on the central server. */
    public static final String PLAYER_ENDPOINT = "/ws/player";

    /**
     * WebSocket close code sent to a player whose connection was replaced by a newer one with the
     * same device id (for example a cloned installation). The player waits before retrying.
     */
    public static final int CLOSE_REPLACED = 4001;

    private ProtocolVersion() {}
}

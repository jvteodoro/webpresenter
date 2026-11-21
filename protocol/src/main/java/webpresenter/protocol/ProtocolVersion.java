package webpresenter.protocol;

/** Version of the server ↔ player protocol, sent by the player in its first message. */
public final class ProtocolVersion {

    /** Incremented on every incompatible change to the message contract. */
    public static final int CURRENT = 1;

    private ProtocolVersion() {}
}

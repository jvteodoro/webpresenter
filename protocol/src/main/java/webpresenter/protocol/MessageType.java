package webpresenter.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Wire names of the messages ({@code Envelope.type}) and their payload classes. */
public enum MessageType {
    HELLO(Message.Hello.class),
    CACHE_STATUS(Message.CacheStatus.class),
    PLAYBACK_STATE(Message.PlaybackState.class),
    PAIRING_PENDING(Message.PairingPending.class),
    PAIRED(Message.Paired.class),
    CONFIG(Message.Config.class),
    PREPARE(Message.Prepare.class),
    PLAY(Message.Play.class),
    PAUSE(Message.Pause.class),
    RESUME(Message.Resume.class),
    SHOW_REST(Message.ShowRest.class),
    REVOKED(Message.Revoked.class),
    ERROR(Message.Error.class);

    private static final Map<Class<?>, MessageType> BY_CLASS =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(t -> t.payloadType, Function.identity()));

    private final Class<? extends Message> payloadType;

    MessageType(Class<? extends Message> payloadType) {
        this.payloadType = payloadType;
    }

    public Class<? extends Message> payloadType() {
        return payloadType;
    }

    public static MessageType of(Message message) {
        return BY_CLASS.get(message.getClass());
    }
}

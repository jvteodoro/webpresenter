package webpresenter.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import webpresenter.protocol.Message.*;

class ProtocolCodecTest {

    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private final ProtocolCodec codec = new ProtocolCodec(Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<Message> messages() {
        var media = new MediaRef("m-1", MediaKind.IMAGE, "http://s3/media/m-1.png?sig", "ab".repeat(32), 1234);
        return Stream.of(
                new Hello("d-1", "sala-1", "0.1.0", 1, List.of(new DisplayInfo(0, 1920, 1080, 1.0, true)), null),
                new Hello("d-1", "sala-1", "0.1.0", 1, List.of(), "secret-token"),
                new CacheStatus("e-1", 2, 5, 1, 1000, 9000),
                new CacheStatus(null, 0, 0, 0, 0, 0),
                new Prepare("e-1", "Culto de domingo", List.of(
                        new PlanItem("i-1", ItemType.MEDIA, "Abertura", media, null),
                        new PlanItem("i-2", ItemType.SLIDE, "Avisos", null, new Slide("Avisos", "Culto de oração\nquarta, 19h30")))),
                Prepare.none(),
                new PairingPending("K7Q-4ZP"),
                new Paired("secret-token"),
                new Config("Telão", 1, media, Transition.DEFAULT),
                new Config("Telão", 0, null, new Transition(TransitionType.CUT, 0)),
                new Revoked("removido"),
                new Message.Error("INVALID_TOKEN", "token inválido"));
    }

    @ParameterizedTest
    @MethodSource("messages")
    void roundTripsEveryMessage(Message message) {
        Envelope decoded = codec.decode(codec.encode(message));

        assertEquals(message, decoded.payload());
        assertEquals(MessageType.of(message), decoded.type());
        assertEquals(NOW, decoded.ts());
    }

    @Test
    void usesReadableWireFormat() {
        String json = codec.encode(new PairingPending("K7Q-4ZP"));

        assertTrue(json.contains("\"type\":\"PAIRING_PENDING\""), json);
        assertTrue(json.contains("\"ts\":\"2026-11-21T15:00:00Z\""), json);
        assertTrue(json.contains("\"payload\":{\"pairingCode\":\"K7Q-4ZP\"}"), json);
    }

    @Test
    void ignoresUnknownFieldsFromNewerPeers() {
        String json = """
                {"type":"PAIRED","id":"1","ts":"2026-11-21T15:00:00Z","payload":{"token":"t","future":42},"extra":true}
                """;

        assertEquals(new Paired("t"), codec.decode(json).payload());
    }

    @Test
    void fieldsMissingFromOlderPeersTakeDefaults() {
        String json = """
                {"type":"CACHE_STATUS","id":"1","ts":"2026-11-21T15:00:00Z","payload":{"itemsReady":1,"itemsTotal":1,"bytesReady":10,"bytesTotal":10}}
                """;

        assertEquals(new CacheStatus(null, 1, 1, 0, 10, 10), codec.decode(json).payload());
    }

    @Test
    void rejectsMalformedMessages() {
        assertThrows(ProtocolCodec.ProtocolException.class, () -> codec.decode("not json"));
        assertThrows(ProtocolCodec.ProtocolException.class, () -> codec.decode("{\"type\":\"NOPE\",\"id\":\"1\",\"ts\":\"2026-11-21T15:00:00Z\",\"payload\":{}}"));
        assertThrows(ProtocolCodec.ProtocolException.class, () -> codec.decode("{\"type\":\"PAIRED\",\"ts\":\"2026-11-21T15:00:00Z\",\"payload\":{}}"));
    }
}

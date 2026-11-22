package webpresenter.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import webpresenter.protocol.Message;

class TimerMathTest {

    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    static final Message.MediaRef BELL = new Message.MediaRef("b", Message.MediaKind.AUDIO, "http://x/b", "00", 1);

    @Test
    void targetIsTheLocalTimeOnTheDayTheItemStarted() {
        Instant start = Instant.parse("2026-11-21T12:40:00Z"); // 09:40 in São Paulo
        var timer = new Message.TimerContent("10:10", null, List.of());
        assertEquals(Instant.parse("2026-11-21T13:10:00Z"), TimerMath.target(timer, start, SAO_PAULO));
    }

    @Test
    void durationCountsFromTheStartAndNoTargetOnlyCountsUp() {
        Instant start = Instant.parse("2026-11-21T12:00:00Z");
        assertEquals(start.plusSeconds(120), TimerMath.target(new Message.TimerContent(null, 120_000L, List.of()), start, SAO_PAULO));
        assertNull(TimerMath.target(new Message.TimerContent(null, null, List.of()), start, SAO_PAULO));
    }

    @Test
    void formatsCountdownAndOvertime() {
        assertEquals("5:00", TimerMath.countdown(300_000));
        assertEquals("5:00", TimerMath.countdown(299_001)); // rounds up: never shows 0:00 before the end
        assertEquals("0:01", TimerMath.countdown(1));
        assertEquals("0:00", TimerMath.countdown(0));
        assertEquals("1:02:03", TimerMath.countdown(3_723_000));
        assertEquals("+0:07", TimerMath.countdown(-7_400));
    }

    @Test
    void signalsFireOnceWhenTheirMomentPasses() {
        Instant target = Instant.parse("2026-11-21T13:10:00Z");
        var fiveMinutes = new Message.TimerSignal(300_000, BELL);
        var end = new Message.TimerSignal(0, BELL);
        List<Message.TimerSignal> signals = List.of(fiveMinutes, end);

        assertEquals(List.of(fiveMinutes), TimerMath.due(signals, target,
                Instant.parse("2026-11-21T13:04:59.800Z"), Instant.parse("2026-11-21T13:05:00.050Z")));
        assertEquals(List.of(), TimerMath.due(signals, target,
                Instant.parse("2026-11-21T13:05:00.050Z"), Instant.parse("2026-11-21T13:05:00.300Z")));
        assertEquals(List.of(end), TimerMath.due(signals, target,
                Instant.parse("2026-11-21T13:09:59.900Z"), Instant.parse("2026-11-21T13:10:00.150Z")));
        assertEquals(List.of(), TimerMath.due(signals, null, Instant.EPOCH, Instant.now()));
    }

    @Test
    void describesTheTimer() {
        Instant target = Instant.parse("2026-11-21T13:10:00Z");
        assertEquals("até 10:10", TimerMath.describe(new Message.TimerContent("10:10:00", null, List.of()), target,
                target.minusSeconds(60)));
        assertEquals("tempo esgotado", TimerMath.describe(new Message.TimerContent("10:10", null, List.of()), target,
                target.plusSeconds(1)));
        assertEquals("de 2:00", TimerMath.describe(new Message.TimerContent(null, 120_000L, List.of()), target,
                target.minusSeconds(100)));
    }
}

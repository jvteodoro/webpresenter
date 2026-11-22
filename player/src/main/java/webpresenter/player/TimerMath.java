package webpresenter.player;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import webpresenter.protocol.Message;

/** Clock and timer arithmetic, in the player's local time. Pure, so it can be tested. */
final class TimerMath {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale.of("pt", "BR"));

    private TimerMath() {}

    /**
     * When the timer reaches zero: its local time on the day the item started, or its duration
     * after the start; null when it has neither (it only counts up).
     */
    static Instant target(Message.TimerContent timer, Instant itemStart, ZoneId zone) {
        if (timer == null) {
            return null;
        }
        if (timer.targetTime() != null) {
            LocalTime time = LocalTime.parse(timer.targetTime());
            return ZonedDateTime.ofInstant(itemStart, zone).with(time).toInstant();
        }
        if (timer.durationMs() != null) {
            return itemStart.plusMillis(timer.durationMs());
        }
        return null;
    }

    /** "4:59", "1:02:03" before the target (rounded up); "+0:07" after it. */
    static String countdown(long remainingMs) {
        boolean over = remainingMs < 0;
        long seconds = over ? (-remainingMs) / 1000 : (remainingMs + 999) / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        String text = h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
        return over ? "+" + text : text;
    }

    /** Signals whose moment (target − beforeEnd) falls in (from, to]. */
    static List<Message.TimerSignal> due(List<Message.TimerSignal> signals, Instant target, Instant from, Instant to) {
        if (target == null) {
            return List.of();
        }
        return signals.stream().filter(s -> {
            Instant at = target.minusMillis(s.beforeEndMs());
            return at.isAfter(from) && !at.isAfter(to);
        }).toList();
    }

    static String clock(Instant now, ZoneId zone) {
        return CLOCK.format(now.atZone(zone));
    }

    static String date(Instant now, ZoneId zone) {
        String text = DATE.format(now.atZone(zone));
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Subtitle of a timer: "até 10:10", "de 2:00", "tempo esgotado". */
    static String describe(Message.TimerContent timer, Instant target, Instant now) {
        if (target != null && now.isAfter(target)) {
            return "tempo esgotado";
        }
        if (timer != null && timer.targetTime() != null) {
            return "até " + timer.targetTime().substring(0, 5);
        }
        if (timer != null && timer.durationMs() != null) {
            return "de " + countdown(timer.durationMs());
        }
        return "";
    }

    static Duration elapsed(Instant start, Instant now) {
        return Duration.between(start, now);
    }
}

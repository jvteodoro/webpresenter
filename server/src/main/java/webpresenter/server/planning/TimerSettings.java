package webpresenter.server.planning;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * How a timer item counts: down to the end of its scene, to a local time, or for a duration from
 * when it starts; then up, past the target. Signals play a library sound at marks before the end.
 *
 * @param time local time for {@link Mode#TIME}
 * @param durationSeconds for {@link Mode#DURATION}
 */
public record TimerSettings(Mode mode, LocalTime time, Integer durationSeconds, List<Signal> signals) {

    public enum Mode { SCENE_END, TIME, DURATION }

    public TimerSettings {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    /** @param beforeEndSeconds 0 = at the end */
    public record Signal(int beforeEndSeconds, UUID soundMediaId) {}
}

package webpresenter.server.live;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Sent Event streams of the live state, one per open web client. */
@Component
class LiveStreams {

    private static final Logger log = LoggerFactory.getLogger(LiveStreams.class);
    /** Clients reconnect transparently after this; keeps abandoned connections from piling up. */
    private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile LiveState last;

    SseEmitter open(LiveState initial) {
        SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, initial);
        return emitter;
    }

    void publish(LiveState state) {
        LiveState previous = last;
        if (previous != null && previous.version() > state.version()) {
            return;
        }
        last = state;
        for (SseEmitter emitter : emitters) {
            send(emitter, state);
        }
    }

    /** A comment line every 15 s keeps idle connections open through proxies and detects dead clients. */
    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    int clientCount() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, LiveState state) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("state").data(state, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("dropping live stream client: {}", e.getMessage());
            emitters.remove(emitter);
        }
    }
}

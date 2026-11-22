package webpresenter.server.jobs;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Sent Event streams of job changes for the queue page and the header badge. */
@Component
class JobStreams {

    private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Opens a stream that starts with the recent jobs ({@code snapshot}), then sends each change ({@code job}). */
    SseEmitter open(List<JobView> snapshot) {
        SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, "snapshot", snapshot);
        return emitter;
    }

    void publish(JobView job) {
        emitters.forEach(e -> send(e, "job", job));
    }

    void removed(UUID id) {
        emitters.forEach(e -> send(e, "removed", Map.of("id", id)));
    }

    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                }
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}

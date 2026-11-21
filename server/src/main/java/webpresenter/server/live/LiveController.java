package webpresenter.server.live;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live presentation: everyone signed in can follow it; leaders, AV technicians and admins control it. */
@RestController
@RequestMapping("/api/live")
class LiveController {

    private final LiveService live;
    private final LiveStreams streams;

    LiveController(LiveService live, LiveStreams streams) {
        this.live = live;
        this.streams = streams;
    }

    @GetMapping
    LiveState state() {
        return live.snapshot();
    }

    /** Server-Sent Events: a "state" event now and after every change. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return streams.open(live.snapshot());
    }

    @PostMapping("/commands")
    @PreAuthorize("hasAnyRole('ADMIN', 'AV_TECHNICIAN', 'WORSHIP_LEADER')")
    LiveState command(@Valid @RequestBody Command command) {
        return switch (command.action()) {
            case PLAY -> {
                if (command.itemId() == null) {
                    throw new LiveService.LiveException("itemId is required for PLAY");
                }
                yield live.play(command.itemId());
            }
            case NEXT -> live.next();
            case PREVIOUS -> live.previous();
            case PAUSE -> live.pause();
            case RESUME -> live.resume();
            case REST -> live.rest();
        };
    }

    enum Action { PLAY, NEXT, PREVIOUS, PAUSE, RESUME, REST }

    record Command(@NotNull Action action, UUID itemId) {}

    @ExceptionHandler(LiveService.LiveException.class)
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

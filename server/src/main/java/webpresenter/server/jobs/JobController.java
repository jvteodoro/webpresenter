package webpresenter.server.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The processing queue: everyone signed in follows it; AV technicians and admins create and manage jobs. */
@RestController
@RequestMapping("/api/jobs")
class JobController {

    private static final String CAN_MANAGE = "hasAnyRole('ADMIN', 'AV_TECHNICIAN')";

    private final JobService jobs;
    private final JobStreams streams;

    JobController(JobService jobs, JobStreams streams) {
        this.jobs = jobs;
        this.streams = streams;
    }

    @GetMapping
    List<JobView> list(@RequestParam(defaultValue = "100") int limit) {
        return jobs.recent(limit);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return streams.open(jobs.recent(100));
    }

    @GetMapping("/{id}")
    JobView get(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @PostMapping("/youtube-import")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    JobView importFromUrl(@Valid @RequestBody ImportRequest request, Authentication user) {
        return jobs.importFromUrl(request.url(), request.target(),
                request.format() == null ? JobService.ImportFormat.AUDIO : request.format(), request.title(),
                user.getName());
    }

    @PostMapping("/separate-playback")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    JobView separatePlayback(@Valid @RequestBody SeparateRequest request, Authentication user) {
        return jobs.separatePlayback(request.songId(), request.recordingId(), user.getName());
    }

    /** Lyrics (if the song has none) and slide times from a sung recording. */
    @PostMapping("/transcribe-lyrics")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    JobView transcribeLyrics(@Valid @RequestBody SeparateRequest request, Authentication user) {
        return jobs.transcribeLyrics(request.songId(), request.recordingId(), user.getName());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CAN_MANAGE)
    JobView cancel(@PathVariable UUID id) {
        return jobs.cancel(id);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize(CAN_MANAGE)
    JobView retry(@PathVariable UUID id) {
        return jobs.retry(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        jobs.delete(id);
    }

    record ImportRequest(@NotBlank @Size(max = 2000) String url, @NotNull JobService.ImportTarget target,
            JobService.ImportFormat format, @Size(max = 300) String title) {}

    record SeparateRequest(@NotNull UUID songId, @NotNull UUID recordingId) {}

    @ExceptionHandler(JobService.NotFoundException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(JobService.InvalidJobException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}

package webpresenter.server.videos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

/** YouTube channels whose video of each event date is downloaded automatically. Everyone signed in sees them. */
@RestController
@RequestMapping("/api/video-sources")
class VideoSourceController {

    private static final String CAN_MANAGE = "hasAnyRole('ADMIN', 'AV_TECHNICIAN')";

    private final VideoSourceService sources;

    VideoSourceController(VideoSourceService sources) {
        this.sources = sources;
    }

    @GetMapping
    List<VideoSourceService.SourceView> list() {
        return sources.list();
    }

    @PostMapping
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    VideoSourceService.SourceView create(@Valid @RequestBody SourceRequest request, Authentication user) {
        var source = sources.create(request.name(), request.channelUrl(), request.enabled() == null || request.enabled(),
                user.getName());
        return sources.list().stream().filter(v -> v.id().equals(source.id())).findFirst().orElseThrow();
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    VideoSourceService.SourceView update(@PathVariable UUID id, @Valid @RequestBody SourceRequest request) {
        var source = sources.update(id, request.name(), request.channelUrl(), request.enabled() == null || request.enabled());
        return sources.list().stream().filter(v -> v.id().equals(source.id())).findFirst().orElseThrow();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        sources.delete(id);
    }

    /** Downloads the source's video of a date now (instead of waiting for the daily search). */
    @PostMapping("/{id}/imports")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    VideoSourceService.ImportView importDay(@PathVariable UUID id, @Valid @RequestBody ImportDayRequest request,
            Authentication user) {
        var attempt = sources.importDay(id, request.date(), user.getName());
        return new VideoSourceService.ImportView(attempt.eventDate(), attempt.status().name(), attempt.message(),
                attempt.mediaId(), attempt.lastAttemptAt());
    }

    /** @param enabled absent means enabled */
    record SourceRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 500) String channelUrl,
            Boolean enabled) {}

    record ImportDayRequest(@NotNull LocalDate date) {}

    @ExceptionHandler(VideoSourceService.NotFoundVideoSourceException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(VideoSourceService.InvalidVideoSourceException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}

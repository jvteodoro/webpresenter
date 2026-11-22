package webpresenter.server.library;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import webpresenter.server.storage.PresignedUrl;

@RestController
@RequestMapping("/api/media")
class MediaController {

    private static final String CAN_MANAGE = "hasAnyRole('ADMIN', 'AV_TECHNICIAN')";

    private final MediaLibraryService library;

    MediaController(MediaLibraryService library) {
        this.library = library;
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize(CAN_MANAGE)
    ResponseEntity<MediaResponse> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "purpose", defaultValue = "GENERAL") MediaPurpose purpose,
            @RequestParam(value = "deduplicate", defaultValue = "false") boolean deduplicate,
            Authentication user) throws IOException {
        var uploaded = library.upload(file.getInputStream(), file.getOriginalFilename(), file.getContentType(),
                title, user.getName(), purpose, deduplicate);
        MediaResponse body = MediaResponse.of(uploaded.asset());
        // Same content already stored: 200 with the existing asset instead of a duplicate.
        return uploaded.created()
                ? ResponseEntity.created(URI.create("/api/media/" + uploaded.asset().id())).body(body)
                : ResponseEntity.ok(body);
    }

    /** Library files; GENERAL only by default, a comma-separated list of purposes, or {@code ALL}. */
    @GetMapping
    List<MediaResponse> list(@RequestParam(value = "purpose", defaultValue = "GENERAL") String purpose) {
        if ("ALL".equalsIgnoreCase(purpose)) {
            return library.list((MediaPurpose) null).stream().map(MediaResponse::of).toList();
        }
        var purposes = java.util.Arrays.stream(purpose.split(","))
                .map(p -> MediaPurpose.valueOf(p.strip().toUpperCase())).distinct().toList();
        return library.list(purposes).stream().map(MediaResponse::of).toList();
    }

    @GetMapping("/{id}")
    MediaResponse get(@PathVariable UUID id) {
        return MediaResponse.of(library.get(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    MediaResponse rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return MediaResponse.of(library.rename(id, request.title()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        library.delete(id);
    }

    /** Short-lived direct link to the file, for previews (browsers cannot add the bearer token to media tags). */
    @GetMapping("/{id}/download-url")
    PresignedUrl downloadUrl(@PathVariable UUID id) {
        return library.downloadUrl(id);
    }

    record RenameRequest(@NotBlank @Size(max = 300) String title) {}

    record MediaResponse(UUID id, String title, String originalFilename, MediaKind kind, String contentType,
            long sizeBytes, String sha256, Long durationMs, Integer width, Integer height, MediaPurpose purpose,
            Instant createdAt, String createdBy) {

        static MediaResponse of(MediaAsset a) {
            return new MediaResponse(a.id(), a.title(), a.originalFilename(), a.kind(), a.contentType(),
                    a.sizeBytes(), a.sha256(), a.durationMs(), a.width(), a.height(), a.purpose(), a.createdAt(),
                    a.createdBy());
        }
    }
}

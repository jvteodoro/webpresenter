package webpresenter.server.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webpresenter.server.playlists.BackgroundSource;
import webpresenter.server.playlists.PlaylistCatalog;

/** Audio settings: admins and AV technicians change everything; whoever controls the live presentation moves the faders. */
@RestController
@RequestMapping("/api/settings")
class SettingsController {

    private final SettingsService settings;
    private final PlaylistCatalog playlists;

    SettingsController(SettingsService settings, PlaylistCatalog playlists) {
        this.settings = settings;
        this.playlists = playlists;
    }

    @GetMapping("/audio")
    AudioView audio() {
        return view(settings.audio());
    }

    @PutMapping("/audio")
    @PreAuthorize("hasAnyRole('ADMIN', 'AV_TECHNICIAN')")
    AudioView updateAudio(@Valid @RequestBody AudioRequest request, Authentication user) {
        return view(settings.updateAudio(new AudioSettings(request.master(), request.media(), request.background(),
                request.effects(), request.fadeInMs(), request.fadeOutMs(), request.duckPercent(),
                request.defaultBackground()), user.getName()));
    }

    @PutMapping("/audio/levels")
    @PreAuthorize("hasAnyRole('ADMIN', 'AV_TECHNICIAN', 'WORSHIP_LEADER')")
    AudioView updateLevels(@Valid @RequestBody LevelsRequest request, Authentication user) {
        return view(settings.updateLevels(new AudioSettings.Levels(request.master(), request.media(),
                request.background(), request.effects()), user.getName()));
    }

    record AudioRequest(@NotNull Integer master, @NotNull Integer media, @NotNull Integer background,
            @NotNull Integer effects, @NotNull Integer fadeInMs, @NotNull Integer fadeOutMs,
            @NotNull Integer duckPercent, BackgroundSource defaultBackground) {}

    record LevelsRequest(@NotNull Integer master, @NotNull Integer media, @NotNull Integer background,
            @NotNull Integer effects) {}

    /** @param defaultBackgroundName display name of the default background music */
    record AudioView(int master, int media, int background, int effects, int fadeInMs, int fadeOutMs, int duckPercent,
            BackgroundSource defaultBackground, String defaultBackgroundName) {}

    private AudioView view(AudioSettings a) {
        return new AudioView(a.master(), a.media(), a.background(), a.effects(), a.fadeInMs(), a.fadeOutMs(),
                a.duckPercent(), a.defaultBackground(), playlists.describe(a.defaultBackground()));
    }

    @ExceptionHandler(SettingsService.InvalidSettingsException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}

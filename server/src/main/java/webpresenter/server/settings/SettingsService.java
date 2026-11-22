package webpresenter.server.settings;

import java.time.Clock;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.playlists.BackgroundSource;
import webpresenter.server.playlists.PlaylistCatalog;

@Service
class SettingsService implements SystemSettings {

    static final String AUDIO = "audio";

    private final AppSettingRepository settings;
    private final PlaylistCatalog playlists;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock = Clock.systemUTC();

    SettingsService(AppSettingRepository settings, PlaylistCatalog playlists, ApplicationEventPublisher events,
            JsonMapper json) {
        this.settings = settings;
        this.playlists = playlists;
        this.events = events;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public AudioSettings audio() {
        return settings.findById(AUDIO).map(s -> json.readValue(s.value(), AudioSettings.class))
                .orElse(AudioSettings.defaults());
    }

    @Transactional
    AudioSettings updateAudio(AudioSettings audio, String user) {
        validate(audio);
        if (audio.defaultBackground() != null) {
            try {
                playlists.validate(audio.defaultBackground());
            } catch (IllegalArgumentException e) {
                throw new InvalidSettingsException(e.getMessage());
            }
        }
        BackgroundSource previous = audio().defaultBackground();
        save(audio, user);
        events.publishEvent(new AudioSettings.Changed(audio, !Objects.equals(previous, audio.defaultBackground())));
        return audio;
    }

    /** Only the faders (live mixer in operation mode). */
    @Transactional
    AudioSettings updateLevels(AudioSettings.Levels levels, String user) {
        AudioSettings audio = audio().withLevels(levels);
        validate(audio);
        save(audio, user);
        events.publishEvent(new AudioSettings.Changed(audio, false));
        return audio;
    }

    private void save(AudioSettings audio, String user) {
        AppSetting setting = settings.findById(AUDIO).orElseGet(() -> new AppSetting(AUDIO));
        setting.set(json.writeValueAsString(audio), user, clock.instant());
        settings.save(setting);
    }

    private static void validate(AudioSettings a) {
        level("master", a.master());
        level("media", a.media());
        level("background", a.background());
        level("effects", a.effects());
        level("duckPercent", a.duckPercent());
        if (a.fadeInMs() < 0 || a.fadeInMs() > 30_000 || a.fadeOutMs() < 0 || a.fadeOutMs() > 30_000) {
            throw new InvalidSettingsException("fades must be between 0 and 30000 ms");
        }
    }

    private static void level(String name, int value) {
        if (value < 0 || value > 100) {
            throw new InvalidSettingsException(name + " must be between 0 and 100");
        }
    }

    static class InvalidSettingsException extends RuntimeException {
        InvalidSettingsException(String message) {
            super(message);
        }
    }
}

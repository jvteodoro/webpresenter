package webpresenter.server.settings;

import webpresenter.server.playlists.BackgroundSource;

/**
 * How players mix sound. Levels are 0..100.
 *
 * @param duckPercent background music level while an item with sound plays (percent of its level; 0 stops it)
 * @param defaultBackground the platform's background music (rest screen, items with nothing more
 *     specific); null = silence
 */
public record AudioSettings(int master, int media, int background, int effects, int fadeInMs, int fadeOutMs,
        int duckPercent, BackgroundSource defaultBackground) {

    public static AudioSettings defaults() {
        return new AudioSettings(100, 100, 60, 100, 2000, 1500, 0, null);
    }

    AudioSettings withLevels(Levels levels) {
        return new AudioSettings(levels.master(), levels.media(), levels.background(), levels.effects(), fadeInMs,
                fadeOutMs, duckPercent, defaultBackground);
    }

    /** The mixer faders, changed live from operation mode. */
    public record Levels(int master, int media, int background, int effects) {}

    /** Published after the settings change. */
    public record Changed(AudioSettings settings, boolean backgroundChanged) {}
}

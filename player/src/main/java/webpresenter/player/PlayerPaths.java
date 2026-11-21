package webpresenter.player;

import java.nio.file.Path;

/**
 * Where the player keeps its identity and its media cache, following each OS's conventions.
 * {@code --data-dir} overrides both (useful for running several players on one machine).
 *
 * @param configDir holds {@code player.properties} (device id and token)
 * @param cacheDir holds downloaded media
 */
public record PlayerPaths(Path configDir, Path cacheDir) {

    public static PlayerPaths resolve(Path dataDirOverride) {
        if (dataDirOverride != null) {
            return new PlayerPaths(dataDirOverride, dataDirOverride.resolve("cache"));
        }
        String os = System.getProperty("os.name").toLowerCase();
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            Path appData = envPath("APPDATA", home.resolve("AppData/Roaming"));
            Path localAppData = envPath("LOCALAPPDATA", home.resolve("AppData/Local"));
            return new PlayerPaths(appData.resolve("webpresenter/player"), localAppData.resolve("webpresenter/player/cache"));
        }
        if (os.contains("mac")) {
            Path support = home.resolve("Library/Application Support/webpresenter-player");
            return new PlayerPaths(support, home.resolve("Library/Caches/webpresenter-player"));
        }
        return new PlayerPaths(envPath("XDG_CONFIG_HOME", home.resolve(".config")).resolve("webpresenter-player"),
                envPath("XDG_CACHE_HOME", home.resolve(".cache")).resolve("webpresenter-player"));
    }

    private static Path envPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}

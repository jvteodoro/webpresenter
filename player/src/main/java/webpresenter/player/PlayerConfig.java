package webpresenter.player;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Player settings. Each value is resolved from a command-line option ({@code --server-url=...}),
 * then an environment variable ({@code WEBPRESENTER_SERVER_URL}), then a default.
 *
 * @param serverUrl base URL of the central server
 * @param windowed open a regular window instead of fullscreen (development)
 * @param dataDir overrides where identity and cache are stored, or null for the OS default
 */
public record PlayerConfig(URI serverUrl, boolean windowed, Path dataDir) {

    public static PlayerConfig from(List<String> args) {
        return new PlayerConfig(
                URI.create(value(args, "server-url").orElse("http://localhost:8090")),
                value(args, "windowed").map(Boolean::parseBoolean).orElse(false),
                value(args, "data-dir").map(Path::of).orElse(null));
    }

    private static Optional<String> value(List<String> args, String name) {
        String prefix = "--" + name;
        for (String arg : args) {
            if (arg.equals(prefix)) {
                return Optional.of("true");
            }
            if (arg.startsWith(prefix + "=")) {
                return Optional.of(arg.substring(prefix.length() + 1));
            }
        }
        String env = System.getenv("WEBPRESENTER_" + name.toUpperCase().replace('-', '_'));
        return Optional.ofNullable(env).filter(v -> !v.isBlank());
    }
}

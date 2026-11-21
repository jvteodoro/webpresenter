package webpresenter.player;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Player settings. Each value is resolved from a command-line option ({@code --server-url=...}),
 * then an environment variable ({@code WEBPRESENTER_SERVER_URL}), then a default.
 *
 * @param serverUrl base URL of the central server
 * @param screen index of the display used for the presentation window
 * @param windowed open a regular window instead of fullscreen (development)
 */
public record PlayerConfig(URI serverUrl, int screen, boolean windowed) {

    public static PlayerConfig from(List<String> args) {
        return new PlayerConfig(
                URI.create(value(args, "server-url").orElse("http://localhost:8090")),
                Integer.parseInt(value(args, "screen").orElse("0")),
                value(args, "windowed").map(Boolean::parseBoolean).orElse(false));
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

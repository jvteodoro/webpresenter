package webpresenter.player;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Player settings. Each value is resolved from a command-line option ({@code --server-url=...}),
 * then an environment variable ({@code WEBPRESENTER_SERVER_URL}), then a default.
 *
 * @param serverUrl base URL of the central server, or null to find it on the network (the server's
 *     beacon announces its current address; the default)
 * @param windowed open a regular window instead of fullscreen (development)
 * @param dataDir overrides where identity and cache are stored, or null for the OS default
 */
public record PlayerConfig(URI serverUrl, boolean windowed, Path dataDir) {

    public static PlayerConfig from(List<String> args) {
        return new PlayerConfig(
                value(args, "server-url").filter(v -> !v.equalsIgnoreCase("auto")).map(URI::create).orElse(null),
                value(args, "windowed").map(Boolean::parseBoolean).orElse(false),
                value(args, "data-dir").map(Path::of).orElse(null));
    }

    /** UDP port of the server's beacon. */
    public static final int BEACON_PORT = Integer.getInteger("webpresenter.beaconPort", 47800);

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

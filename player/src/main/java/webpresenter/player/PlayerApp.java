package webpresenter.player;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.input.KeyCode;
import javafx.stage.Screen;
import javafx.stage.Stage;
import webpresenter.player.cache.MediaCache;
import webpresenter.player.net.ServerConnection;
import webpresenter.protocol.Message;
import webpresenter.protocol.ProtocolCodec;
import webpresenter.protocol.ProtocolVersion;

/** MediaPlayer entry point: wires identity, cache, server connection and the output window. */
public final class PlayerApp extends Application {

    private static final System.Logger LOG = System.getLogger(PlayerApp.class.getName());
    static final String VERSION = versionOrDev();

    private PresentationWindow window;
    private PlayerController controller;
    private ServerConnection connection;
    private MediaCache cache;

    @Override
    public void start(Stage stage) {
        PlayerConfig config = PlayerConfig.from(getParameters().getRaw());
        PlayerPaths paths = PlayerPaths.resolve(config.dataDir());
        PlayerIdentity identity = PlayerIdentity.load(paths.configDir());
        cache = new MediaCache(paths.cacheDir());
        String hostname = hostname();

        AtomicReference<List<Message.DisplayInfo>> displays = new AtomicReference<>(displays());
        Screen.getScreens().addListener((ListChangeListener<Screen>) change -> displays.set(displays()));

        window = new PresentationWindow(stage, config.windowed());
        DebugSnapshots snapshots = DebugSnapshots.fromSystemProperty(window.scene());
        controller = new PlayerController(window, identity, cache, config.serverUrl().toString(),
                snapshots::capture);

        connection = new ServerConnection(config.serverUrl(), new ProtocolCodec(),
                () -> new Message.Hello(identity.deviceId().toString(), hostname, VERSION, ProtocolVersion.CURRENT,
                        displays.get(), identity.token().orElse(null)),
                controller);
        controller.attach(connection);
        connection.start();

        window.scene().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && e.isShiftDown()) {
                Platform.exit(); // Shift+Esc: avoids closing the output by accident during an event
            }
        });
        LOG.log(System.Logger.Level.INFO, "player {0} (device {1}) on {2}, data in {3}", VERSION, identity.deviceId(),
                hostname, paths.configDir());
    }

    @Override
    public void stop() {
        if (connection != null) {
            connection.close();
        }
        if (controller != null) {
            controller.close();
        }
        if (window != null) {
            window.close();
        }
        if (cache != null) {
            cache.close();
        }
    }

    private static List<Message.DisplayInfo> displays() {
        Screen primary = Screen.getPrimary();
        List<Screen> screens = Screen.getScreens();
        return screens.stream().map(s -> new Message.DisplayInfo(screens.indexOf(s), (int) s.getBounds().getWidth(),
                (int) s.getBounds().getHeight(), s.getOutputScaleX(), s.equals(primary))).toList();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv(System.getProperty("os.name").toLowerCase().contains("win") ? "COMPUTERNAME" : "HOSTNAME");
            return env == null ? "player" : env;
        }
    }

    private static String versionOrDev() {
        String v = PlayerApp.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** Writes the composited window to PPM files on state changes when -Dplayer.snapshotDir is set (testing). */
    static final class DebugSnapshots {
        private final javafx.scene.Scene scene;
        private final Path dir;
        private int counter;

        private DebugSnapshots(javafx.scene.Scene scene, Path dir) {
            this.scene = scene;
            this.dir = dir;
        }

        static DebugSnapshots fromSystemProperty(javafx.scene.Scene scene) {
            String dir = System.getProperty("player.snapshotDir");
            return new DebugSnapshots(scene, dir == null ? null : Path.of(dir));
        }

        void capture() {
            if (dir == null) {
                return;
            }
            int n = ++counter;
            // Let fades finish before capturing.
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
            wait.setOnFinished(e -> write(n));
            wait.play();
        }

        private void write(int n) {
            var image = scene.snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            byte[] rgb = new byte[w * h * 3];
            int[] row = new int[w];
            var reader = image.getPixelReader();
            for (int y = 0; y < h; y++) {
                reader.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
                for (int x = 0; x < w; x++) {
                    int o = (y * w + x) * 3;
                    rgb[o] = (byte) (row[x] >> 16);
                    rgb[o + 1] = (byte) (row[x] >> 8);
                    rgb[o + 2] = (byte) row[x];
                }
            }
            try {
                Files.createDirectories(dir);
                try (var out = Files.newOutputStream(dir.resolve(String.format("state-%02d.ppm", n)))) {
                    out.write(("P6\n" + w + " " + h + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.write(rgb);
                }
            } catch (java.io.IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "snapshot failed: {0}", ex.getMessage());
            }
        }
    }
}

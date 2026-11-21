package webpresenter.spike;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import webpresenter.spike.mpv.MpvEngine;

/**
 * M0 spike: a fullscreen presentation window that plays video, audio, images, text slides and a
 * rest screen with fade transitions, with a JavaFX text overlay composited on top of the video.
 *
 * <p>Keys: 1 video, 2 image, 3 text slide, 4 audio, R rest screen, O toggle overlay, D toggle
 * stats, Esc quit. With {@code --auto} it runs a scripted sequence, prints metrics and exits.
 */
public final class SpikeApp extends Application {

    enum Kind { REST, VIDEO, AUDIO, IMAGE, SLIDE }

    private static final Duration FADE = Duration.millis(600);

    private VideoEngine engine;
    private Path mediaDir;
    private final Map<Kind, Node> layers = new HashMap<>();
    private Kind current;
    private final Label overlay = new Label("Grande é o Senhor e mui digno de louvor\nna cidade do nosso Deus");
    private final Label stats = new Label();
    private final DoubleProperty volume = new SimpleDoubleProperty(100);
    private final List<String> metricLog = new ArrayList<>();
    private int pulses;
    private boolean auto;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Map<String, String> opts = parseArgs(getParameters().getRaw());
        auto = opts.containsKey("auto");
        boolean windowed = opts.containsKey("windowed");
        mediaDir = Path.of(opts.getOrDefault("media-dir", "media"));

        List<Screen> screens = Screen.getScreens();
        int screenIndex = Integer.parseInt(opts.getOrDefault("screen", "0"));
        Screen screen = screens.get(Math.min(screenIndex, screens.size() - 1));
        Rectangle2D bounds = screen.getBounds();
        double winW = windowed ? 1280 : bounds.getWidth();
        double winH = windowed ? 720 : bounds.getHeight();
        int renderW = (int) Math.round(winW * screen.getOutputScaleX());
        int renderH = (int) Math.round(winH * screen.getOutputScaleY());
        if (opts.containsKey("render")) {
            String[] wh = opts.get("render").split("x");
            renderW = Integer.parseInt(wh[0]);
            renderH = Integer.parseInt(wh[1]);
        }

        engine = createEngine(opts.getOrDefault("engine", "mpv"), renderW, renderH);
        engine.onEndOfMedia(() -> Platform.runLater(() -> {
            if (current == Kind.VIDEO || current == Kind.AUDIO) {
                show(Kind.REST);
            }
        }));
        volume.addListener((obs, o, n) -> engine.setVolume(n.doubleValue()));

        StackPane root = new StackPane();
        root.setBackground(new Background(new BackgroundFill(Color.BLACK, null, null)));

        ImageView video = (ImageView) engine.view();
        video.fitWidthProperty().bind(root.widthProperty());
        video.fitHeightProperty().bind(root.heightProperty());
        layers.put(Kind.VIDEO, video);
        layers.put(Kind.AUDIO, audioLayer());
        layers.put(Kind.IMAGE, imageLayer(root));
        layers.put(Kind.SLIDE, slideLayer());
        layers.put(Kind.REST, restLayer(root));
        for (Kind k : Kind.values()) {
            Node layer = layers.get(k);
            layer.setOpacity(0);
            layer.setVisible(false);
            root.getChildren().add(layer);
        }

        overlay.setFont(Font.font("System", FontWeight.BOLD, 54));
        overlay.setTextFill(Color.WHITE);
        overlay.setTextAlignment(TextAlignment.CENTER);
        overlay.setEffect(new DropShadow(12, Color.BLACK));
        overlay.setOpacity(0);
        overlay.setCache(true);
        overlay.setCacheHint(javafx.scene.CacheHint.SPEED);
        StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);
        StackPane.setMargin(overlay, new Insets(0, 0, 80, 0));

        stats.setFont(Font.font("Monospaced", 16));
        stats.setTextFill(Color.LIME);
        stats.setBackground(new Background(new BackgroundFill(Color.rgb(0, 0, 0, 0.6), CornerRadii.EMPTY, null)));
        stats.setPadding(new Insets(6));
        StackPane.setAlignment(stats, Pos.TOP_LEFT);
        root.getChildren().addAll(overlay, stats);

        Scene scene = new Scene(root, winW, winH, Color.BLACK);
        scene.setCursor(windowed ? Cursor.DEFAULT : Cursor.NONE);
        scene.setOnKeyPressed(e -> {
            KeyCode code = e.getCode();
            switch (code) {
                case DIGIT1 -> show(Kind.VIDEO);
                case DIGIT2 -> show(Kind.IMAGE);
                case DIGIT3 -> show(Kind.SLIDE);
                case DIGIT4 -> show(Kind.AUDIO);
                case R -> show(Kind.REST);
                case O -> toggleOverlay();
                case D -> stats.setVisible(!stats.isVisible());
                case ESCAPE -> Platform.exit();
                default -> {}
            }
        });

        stage.setTitle("webpresenter player spike — " + engine.name());
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        if (!windowed) {
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setFullScreenExitHint("");
            stage.setFullScreen(true);
        }
        stage.setScene(scene);
        stage.show();

        startMetrics(renderW, renderH);
        show(Kind.REST);
        if (auto) {
            runAutoSequence();
        }
    }

    @Override
    public void stop() {
        if (engine != null) {
            engine.close();
        }
        if (auto) {
            System.out.println("=== metrics (" + (engine == null ? "?" : engine.name()) + ") ===");
            metricLog.forEach(System.out::println);
        }
    }

    // ---------------------------------------------------------------- transitions

    private void show(Kind next) {
        Kind previous = current;
        current = next;
        Node out = previous == null ? null : layers.get(previous);
        Node in = layers.get(next);

        SequentialTransition seq = new SequentialTransition();
        if (out != null && previous != next) {
            FadeTransition fadeOut = new FadeTransition(FADE, out);
            fadeOut.setToValue(0);
            ParallelTransition outPhase = new ParallelTransition(fadeOut);
            if (isMedia(previous)) {
                outPhase.getChildren().add(volumeRamp(0));
            }
            seq.getChildren().add(outPhase);
        }
        seq.setOnFinished(e -> {
            if (out != null && previous != next) {
                out.setVisible(false);
            }
            if (isMedia(previous) && !isMedia(next)) {
                engine.stop();
            }
            if (isMedia(next)) {
                volume.set(0);
                engine.play(mediaDir.resolve(next == Kind.VIDEO ? "video.mp4" : "audio.m4a"));
            }
            in.setVisible(true);
            FadeTransition fadeIn = new FadeTransition(FADE, in);
            fadeIn.setToValue(1);
            ParallelTransition inPhase = new ParallelTransition(fadeIn);
            if (isMedia(next)) {
                inPhase.getChildren().add(volumeRamp(100));
            }
            inPhase.play();
        });
        seq.play();
        log("show " + next);
    }

    private Timeline volumeRamp(double target) {
        return new Timeline(new KeyFrame(FADE, new KeyValue(volume, target, Interpolator.EASE_BOTH)));
    }

    private void toggleOverlay() {
        FadeTransition f = new FadeTransition(Duration.millis(300), overlay);
        f.setToValue(overlay.getOpacity() > 0.5 ? 0 : 1);
        f.play();
    }

    private static boolean isMedia(Kind k) {
        return k == Kind.VIDEO || k == Kind.AUDIO;
    }

    // ---------------------------------------------------------------- layers

    private Node imageLayer(Region root) {
        ImageView iv = new ImageView();
        Path p = mediaDir.resolve("image.jpg");
        if (Files.exists(p)) {
            iv.setImage(new Image(p.toUri().toString(), true));
        }
        iv.setPreserveRatio(true);
        iv.fitWidthProperty().bind(root.widthProperty());
        iv.fitHeightProperty().bind(root.heightProperty());
        return iv;
    }

    private Node slideLayer() {
        Label text = new Label("Avisos\n\nCulto de oração — quarta-feira, 19h30\nEscola bíblica — domingo, 9h");
        text.setFont(Font.font("System", FontWeight.SEMI_BOLD, 60));
        text.setTextFill(Color.WHITE);
        text.setTextAlignment(TextAlignment.CENTER);
        text.setWrapText(true);
        StackPane pane = new StackPane(text);
        pane.setBackground(new Background(new BackgroundFill(
                new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#1d2b53")), new Stop(1, Color.web("#0b1020"))),
                null, null)));
        pane.setPadding(new Insets(80));
        return pane;
    }

    private Node audioLayer() {
        Label text = new Label("♪  Reproduzindo áudio");
        text.setFont(Font.font("System", FontWeight.BOLD, 48));
        text.setTextFill(Color.web("#e0e0e0"));
        StackPane pane = new StackPane(text);
        pane.setBackground(new Background(new BackgroundFill(Color.web("#101010"), null, null)));
        return pane;
    }

    /** Rest screen: an image if provided, otherwise an animated gradient (exercises continuous redraw). */
    private Node restLayer(Region root) {
        Path p = mediaDir.resolve("rest.jpg");
        if (Files.exists(p)) {
            ImageView iv = new ImageView(new Image(p.toUri().toString(), true));
            iv.setPreserveRatio(true);
            iv.fitWidthProperty().bind(root.widthProperty());
            iv.fitHeightProperty().bind(root.heightProperty());
            return iv;
        }
        Region bg = new Region();
        DoubleProperty phase = new SimpleDoubleProperty(0);
        phase.addListener((obs, o, n) -> {
            double t = n.doubleValue();
            bg.setBackground(new Background(new BackgroundFill(new LinearGradient(
                    0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.hsb(220 + 30 * Math.sin(t), 0.6, 0.35)),
                    new Stop(1, Color.hsb(280 + 30 * Math.cos(t), 0.6, 0.15))), null, null)));
        });
        Timeline anim = new Timeline(new KeyFrame(Duration.seconds(20), new KeyValue(phase, Math.PI * 2)));
        anim.setCycleCount(Timeline.INDEFINITE);
        anim.play();
        Label welcome = new Label("Bem-vindos");
        welcome.setFont(Font.font("System", FontWeight.LIGHT, 96));
        welcome.setTextFill(Color.WHITE);
        return new StackPane(bg, welcome);
    }

    // ---------------------------------------------------------------- metrics

    private void startMetrics(int renderW, int renderH) {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                pulses++;
            }
        }.start();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        int cores = os.getAvailableProcessors();
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            VideoEngine.RenderStats s = engine.drainStats();
            double cpu = os.getProcessCpuLoad() * 100 * cores; // % of one core, like top
            String line = String.format(Locale.ROOT,
                    "%-5s fx=%3d fps  frames=%3d  render avg=%5.1f ms max=%5.1f ms  cpu=%5.0f%%  heap=%4d MB  [%dx%d %s]",
                    current, pulses, s.framesRendered(), s.avgRenderMs(), s.maxRenderMs(), cpu,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20,
                    renderW, renderH, engine.name());
            pulses = 0;
            stats.setText(line);
            log(line);
        }));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
    }

    private void log(String line) {
        System.out.println(line);
        if (auto) {
            metricLog.add(line);
        }
    }

    // ---------------------------------------------------------------- auto mode

    private void runAutoSequence() {
        SequentialTransition script = new SequentialTransition(
                step(2.5, () -> snapshot("1-rest")),
                step(0.5, () -> show(Kind.VIDEO)),
                step(4, this::toggleOverlay),
                step(2, () -> snapshot("2-video-overlay")),
                step(2, this::toggleOverlay),
                step(1, () -> show(Kind.IMAGE)),
                step(2, () -> snapshot("3-image")),
                step(1, () -> show(Kind.SLIDE)),
                step(0.9, () -> snapshot("4-slide-crossfade")),
                step(2.1, () -> show(Kind.AUDIO)),
                step(4, () -> show(Kind.VIDEO)),
                step(3, () -> show(Kind.REST)),
                step(2, Platform::exit));
        script.play();
    }

    /** Writes the composited scene to a PPM file (no Swing/ImageIO dependency needed). */
    private void snapshot(String name) {
        String dir = System.getProperty("spike.snapshotDir");
        if (dir == null) {
            return;
        }
        javafx.scene.image.WritableImage img = stats.getScene().snapshot(null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        javafx.scene.image.PixelReader reader = img.getPixelReader();
        byte[] rgb = new byte[w * h * 3];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            reader.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int o = (y * w + x) * 3;
                rgb[o] = (byte) (argb >> 16);
                rgb[o + 1] = (byte) (argb >> 8);
                rgb[o + 2] = (byte) argb;
            }
        }
        try (var out = Files.newOutputStream(Path.of(dir, name + ".ppm"))) {
            out.write(("P6\n" + w + " " + h + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(rgb);
        } catch (java.io.IOException e) {
            System.err.println("snapshot failed: " + e);
        }
        log("snapshot " + name);
    }

    private static PauseTransition step(double seconds, Runnable action) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> action.run());
        return p;
    }

    // ---------------------------------------------------------------- setup

    private static VideoEngine createEngine(String name, int w, int h) {
        return switch (name) {
            case "mpv" -> new MpvEngine(w, h);
            default -> throw new IllegalArgumentException("unknown engine: " + name);
        };
    }

    private static Map<String, String> parseArgs(List<String> raw) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            String a = raw.get(i);
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            int eq = key.indexOf('=');
            if (eq >= 0) {
                opts.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < raw.size() && !raw.get(i + 1).startsWith("--")) {
                opts.put(key, raw.get(++i));
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }
}

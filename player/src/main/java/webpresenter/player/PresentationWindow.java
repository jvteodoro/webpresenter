package webpresenter.player;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
import webpresenter.player.engine.VideoEngine;
import webpresenter.player.engine.mpv.MpvEngine;
import webpresenter.protocol.Message;

/**
 * The output window on the chosen display. Layers, bottom to top: rest video, rest image, item
 * video, item image, slide, and an information panel (connection state, pairing code) shown only
 * when nothing is presented.
 *
 * <p>Two playback engines: one loops the rest screen video (muted), the other plays event items.
 * Audio items therefore play over the rest screen. All methods must be called on the JavaFX thread.
 */
final class PresentationWindow {

    private static final System.Logger LOG = System.getLogger(PresentationWindow.class.getName());
    private static final double FULL_VOLUME = 100;

    private final Stage stage;
    private final boolean windowed;
    private final StackPane root = new StackPane();
    private final ImageView restImage = fitted(new ImageView());
    private final ImageView itemImage = fitted(new ImageView());
    private final StackPane slide = new StackPane();
    /** Song lyrics over the song background. */
    private final StackPane song = new StackPane();
    private final ImageView songBackground = fitted(new ImageView());
    private final Label songCue = label(FontWeight.NORMAL, Color.web("#ffd27a"));
    private final Label songLines = label(FontWeight.BOLD, Color.WHITE);
    private final VBox songText = new VBox(18);
    private final Label slideTitle = label(FontWeight.BOLD, Color.WHITE);
    private final Label slideBody = label(FontWeight.NORMAL, Color.gray(0.92));
    /** Clock or timer item: label, big value, a line below. */
    private final StackPane clock = new StackPane();
    private final Label clockLabel = label(FontWeight.NORMAL, Color.gray(0.85));
    private final Label clockValue = label(FontWeight.BOLD, Color.WHITE);
    private final Label clockSub = label(FontWeight.NORMAL, Color.gray(0.7));
    /** The time over the rest screen (device setting). */
    private final Label restClock = label(FontWeight.BOLD, Color.WHITE);
    private boolean restClockEnabled;
    /** The information panel (connection, pairing) is up: it hides the rest clock. */
    private boolean infoShown;
    private final VBox info = new VBox(18);
    private final Label infoTitle = label(FontWeight.LIGHT, Color.gray(0.85));
    private final Label infoCode = label(FontWeight.BOLD, Color.WHITE);
    private final Label infoDetail = label(FontWeight.NORMAL, Color.gray(0.6));
    private final Map<Node, FadeTransition> fades = new HashMap<>();
    private final DoubleProperty itemVolume = new SimpleDoubleProperty(0);
    private double mediaGain = 1;

    private VideoEngine restEngine;
    private VideoEngine itemEngine;
    private Node restVideo;
    private Node itemVideo;
    private Screen screen;
    private Duration fade = Duration.millis(Message.Transition.DEFAULT.durationMs());
    private Timeline volumeRamp;

    private Rest rest = Rest.NONE;
    private Item item = Item.NONE;
    private Runnable onItemEnded = () -> {};

    private enum RestKind { NONE, IMAGE, VIDEO }

    private record Rest(RestKind kind, Path file) {
        static final Rest NONE = new Rest(RestKind.NONE, null);
    }

    /** What is presented on top of the rest screen. */
    enum ItemKind { NONE, VIDEO, AUDIO, IMAGE, SLIDE, SONG, CLOCK }

    private record Item(ItemKind kind, Path file, long startMs) {
        static final Item NONE = new Item(ItemKind.NONE, null, 0);
    }

    PresentationWindow(Stage stage, boolean windowed) {
        this.stage = stage;
        this.windowed = windowed;

        root.setStyle("-fx-background-color: black;");
        bindFit(restImage);
        bindFit(itemImage);
        restImage.setOpacity(0);
        itemImage.setOpacity(0);

        VBox slideText = new VBox(28, slideTitle, slideBody);
        slideText.setAlignment(Pos.CENTER);
        slide.getChildren().add(slideText);
        slide.setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 1, 1, true,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#1d2b53")), new Stop(1, Color.web("#0b1020"))), null, null)));
        slide.setOpacity(0);
        slide.setVisible(false);

        // Song: background image filling the output (cropped, it is decorative), darkened for contrast,
        // lyrics centred with a shadow.
        javafx.beans.InvalidationListener cover = o -> cover(songBackground);
        root.widthProperty().addListener(cover);
        root.heightProperty().addListener(cover);
        songBackground.imageProperty().addListener(cover);
        javafx.scene.layout.Region shade = new javafx.scene.layout.Region();
        shade.setStyle("-fx-background-color: rgba(0,0,0,0.45);");
        songText.setAlignment(Pos.CENTER);
        songText.getChildren().addAll(songCue, songLines);
        javafx.scene.effect.DropShadow shadow = new javafx.scene.effect.DropShadow(18, Color.color(0, 0, 0, 0.85));
        shadow.setSpread(0.25);
        songText.setEffect(shadow);
        song.getChildren().addAll(songBackground, shade, songText);
        // The covering image is larger than the output: do not let it grow the pane, crop it.
        song.setMinSize(0, 0);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(song.widthProperty());
        clip.heightProperty().bind(song.heightProperty());
        song.setClip(clip);
        song.setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 1, 1, true,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#1d2b53")), new Stop(1, Color.web("#0b1020"))), null, null)));
        song.setOpacity(0);
        song.setVisible(false);

        infoCode.setStyle("-fx-font-family: 'Monospaced';");
        info.setAlignment(Pos.CENTER);
        info.setPadding(new Insets(48));
        info.getChildren().addAll(infoTitle, infoCode, infoDetail);

        VBox clockText = new VBox(12, clockLabel, clockValue, clockSub);
        clockText.setAlignment(Pos.CENTER);
        clockValue.setStyle("-fx-font-family: 'Monospaced';");
        clock.getChildren().add(clockText);
        clock.setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 1, 1, true,
                CycleMethod.NO_CYCLE, new Stop(0, Color.web("#1d2b53")), new Stop(1, Color.web("#0b1020"))), null, null)));
        clock.setOpacity(0);
        clock.setVisible(false);
        javafx.scene.effect.DropShadow clockShadow = new javafx.scene.effect.DropShadow(24, Color.color(0, 0, 0, 0.9));
        clockShadow.setSpread(0.3);
        restClock.setEffect(clockShadow);
        restClock.setOpacity(0);
        restClock.setMouseTransparent(true);

        root.getChildren().addAll(restImage, itemImage, restClock, slide, song, clock, info);
        // Text scales with the output height, so slides read the same on any display.
        root.heightProperty().addListener((obs, o, h) -> scaleText(h.doubleValue()));
        root.widthProperty().addListener((obs, o, w) -> {
            Insets margins = new Insets(0, w.doubleValue() * 0.08, 0, w.doubleValue() * 0.08);
            slide.setPadding(margins);
            song.setPadding(margins);
        });

        itemVolume.addListener((obs, o, v) -> applyItemVolume());

        Scene scene = new Scene(root, 1280, 720, Color.BLACK);
        scene.setCursor(windowed ? Cursor.DEFAULT : Cursor.NONE);
        stage.setScene(scene);
        stage.setTitle("webpresenter player");
        if (!windowed) {
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setFullScreenExitHint("");
        }
        moveTo(0);
        stage.show();
        scaleText(root.getHeight() > 0 ? root.getHeight() : 720);
    }

    Scene scene() {
        return stage.getScene();
    }

    void setTransition(Message.Transition transition) {
        fade = Duration.millis(transition.type() == Message.TransitionType.CUT ? 1 : Math.max(1, transition.durationMs()));
    }

    /** Called (on the JavaFX thread) when an item video or audio reaches its end. */
    void setOnItemEnded(Runnable listener) {
        this.onItemEnded = listener;
    }

    // ------------------------------------------------------------------ display and engines

    /** Moves the window to a display, recreating the engines if the output resolution changes. */
    void moveTo(int displayIndex) {
        List<Screen> screens = Screen.getScreens();
        Screen target = screens.get(displayIndex >= 0 && displayIndex < screens.size() ? displayIndex : 0);
        if (target.equals(screen) && restEngine != null) {
            return;
        }
        screen = target;
        Rectangle2D bounds = target.getBounds();
        double width = windowed ? 1280 : bounds.getWidth();
        double height = windowed ? 720 : bounds.getHeight();
        stage.setFullScreen(false);
        stage.setX(bounds.getMinX() + (windowed ? 40 : 0));
        stage.setY(bounds.getMinY() + (windowed ? 40 : 0));
        stage.setWidth(width);
        stage.setHeight(height);
        if (!windowed) {
            stage.setFullScreen(true);
        }
        replaceEngines((int) Math.round(width * target.getOutputScaleX()),
                (int) Math.round(height * target.getOutputScaleY()));
        LOG.log(System.Logger.Level.INFO, "presenting on display {0} {1}", screens.indexOf(target), bounds);
    }

    private void replaceEngines(int width, int height) {
        if (restEngine != null) {
            root.getChildren().removeAll(restVideo, itemVideo);
            restEngine.close();
            itemEngine.close();
        }
        restEngine = new MpvEngine(width, height);
        restEngine.setVolume(0); // rest screens are visual only
        itemEngine = new MpvEngine(width, height);
        applyItemVolume();
        itemEngine.onEndOfMedia(() -> javafx.application.Platform.runLater(this::itemMediaEnded));
        restVideo = bindFit(restEngine.view());
        itemVideo = bindFit(itemEngine.view());
        restVideo.setOpacity(rest.kind() == RestKind.VIDEO ? 1 : 0);
        itemVideo.setOpacity(item.kind() == ItemKind.VIDEO ? 1 : 0);
        root.getChildren().add(0, restVideo);
        root.getChildren().add(root.getChildren().indexOf(itemImage), itemVideo);
        if (rest.kind() == RestKind.VIDEO) {
            restEngine.play(rest.file(), true);
        }
        if (item.kind() == ItemKind.VIDEO || item.kind() == ItemKind.AUDIO
                || (item.kind() == ItemKind.SONG && item.file() != null)) {
            itemEngine.play(item.file(), false, item.startMs());
        }
    }

    // ------------------------------------------------------------------ rest screen

    boolean isPresenting() {
        return rest.kind() != RestKind.NONE || item.kind() != ItemKind.NONE;
    }

    void setRestImage(Path file) {
        Rest previous = rest;
        rest = new Rest(RestKind.IMAGE, file);
        restImage.setImage(new Image(file.toUri().toString()));
        hideInfo();
        fadeTo(restImage, 1);
        if (previous.kind() == RestKind.VIDEO) {
            fadeTo(restVideo, 0).setOnFinished(e -> stopRestVideoIfUnused());
        }
    }

    void setRestVideo(Path file) {
        Rest previous = rest;
        rest = new Rest(RestKind.VIDEO, file);
        hideInfo();
        restEngine.play(file, true);
        if (item.kind() == ItemKind.VIDEO) {
            restEngine.setPaused(true); // hidden behind an item video
        }
        fadeTo(restVideo, 1);
        if (previous.kind() == RestKind.IMAGE) {
            fadeTo(restImage, 0);
        }
    }

    /** No rest screen configured: black output behind items. */
    void clearRest() {
        Rest previous = rest;
        rest = Rest.NONE;
        fadeTo(restImage, 0);
        if (previous.kind() == RestKind.VIDEO) {
            fadeTo(restVideo, 0).setOnFinished(e -> stopRestVideoIfUnused());
        }
    }

    private void stopRestVideoIfUnused() {
        if (rest.kind() != RestKind.VIDEO) {
            restEngine.stop();
        }
    }

    // ------------------------------------------------------------------ event items

    ItemKind itemKind() {
        return item.kind();
    }

    void showVideo(Path file, long startMs) {
        enterItem(new Item(ItemKind.VIDEO, file, startMs));
        itemEngine.play(file, false, startMs);
        rampVolume(FULL_VOLUME);
        fadeTo(itemVideo, 1).setOnFinished(e -> {
            if (item.kind() == ItemKind.VIDEO && rest.kind() == RestKind.VIDEO) {
                restEngine.setPaused(true); // invisible: save CPU
            }
        });
        fadeTo(itemImage, 0);
        hideSlide();
        hideSong();
    }

    /** Audio plays over the rest screen. */
    void showAudio(Path file, long startMs) {
        enterItem(new Item(ItemKind.AUDIO, file, startMs));
        itemEngine.play(file, false, startMs);
        rampVolume(FULL_VOLUME);
        fadeTo(itemVideo, 0);
        fadeTo(itemImage, 0);
        hideSlide();
        hideSong();
        resumeRestVideo();
    }

    void showImage(Path file) {
        enterItem(new Item(ItemKind.IMAGE, file, 0));
        itemImage.setImage(new Image(file.toUri().toString()));
        fadeTo(itemImage, 1);
        hideSlide();
        hideSong();
        stopItemMedia();
    }

    void showSlide(String title, String body) {
        enterItem(new Item(ItemKind.SLIDE, null, 0));
        slideTitle.setText(title == null ? "" : title);
        slideBody.setText(body == null ? "" : body);
        slideBody.setManaged(body != null && !body.isBlank());
        slide.setVisible(true);
        fadeTo(slide, 1);
        fadeTo(itemImage, 0);
        hideSong();
        hideClock();
        stopItemMedia();
    }

    /** A clock or timer item; its texts are set with {@link #setClock}. */
    void showClock(String label) {
        enterItem(new Item(ItemKind.CLOCK, null, 0));
        clockLabel.setText(label == null ? "" : label);
        clockLabel.setManaged(label != null && !label.isBlank());
        clock.setVisible(true);
        fadeTo(clock, 1);
        fadeTo(itemImage, 0);
        hideSlide();
        hideSong();
        stopItemMedia();
        updateRestClock();
    }

    /** @param alert overtime: the value turns red */
    void setClock(String value, String sub, boolean alert) {
        clockValue.setText(value);
        clockValue.setTextFill(alert ? Color.web("#ff6b6b") : Color.WHITE);
        clockSub.setText(sub == null ? "" : sub);
        clockSub.setManaged(sub != null && !sub.isBlank());
    }

    /** Shows the time over the rest screen (device setting). */
    void setRestClock(boolean enabled) {
        restClockEnabled = enabled;
        updateRestClock();
    }

    void setRestClockText(String text) {
        restClock.setText(text);
    }

    private void updateRestClock() {
        boolean show = restClockEnabled && item.kind() == ItemKind.NONE && !infoShown;
        fadeTo(restClock, show ? 1 : 0);
    }

    private void hideClock() {
        FadeTransition t = fadeTo(clock, 0);
        t.setOnFinished(e -> {
            fades.remove(clock, t);
            if (item.kind() != ItemKind.CLOCK) {
                clock.setVisible(false);
            }
        });
    }

    /**
     * Starts a song: its background (or the default one), and its audio if any, from
     * {@code startMs}. Lyrics are set with {@link #showSongSlide}.
     */
    void showSong(Path background, Path audio, long startMs) {
        enterItem(new Item(ItemKind.SONG, audio, startMs));
        songBackground.setImage(background == null ? null : new Image(background.toUri().toString()));
        songLines.setText("");
        songCue.setText("");
        song.setVisible(true);
        fadeTo(song, 1);
        fadeTo(itemImage, 0);
        hideSlide();
        if (audio != null) {
            itemEngine.play(audio, false, startMs);
            rampVolume(FULL_VOLUME);
            FadeTransition t = fadeTo(itemVideo, 0);
            t.setOnFinished(e -> fades.remove(itemVideo, t));
        } else {
            stopItemMedia();
        }
    }

    /** Lyrics on screen (empty lines: background only, for intros and instrumental parts). */
    void showSongSlide(java.util.List<String> lines, String cue) {
        String text = String.join("\n", lines);
        if (text.equals(songLines.getText()) && java.util.Objects.equals(cue == null ? "" : cue, songCue.getText())) {
            return;
        }
        FadeTransition out = new FadeTransition(Duration.millis(150), songText);
        out.setToValue(0);
        out.setOnFinished(e -> {
            songLines.setText(text);
            songCue.setText(cue == null ? "" : cue);
            songCue.setManaged(cue != null && !cue.isBlank());
            FadeTransition in = new FadeTransition(Duration.millis(150), songText);
            in.setToValue(1);
            in.play();
        });
        out.play();
    }

    void seekItem(long positionMs) {
        if (item.kind() == ItemKind.SONG || item.kind() == ItemKind.VIDEO || item.kind() == ItemKind.AUDIO) {
            itemEngine.seek(positionMs);
        }
    }

    /** Leaves the current item and shows the rest screen. */
    void showRest() {
        item = Item.NONE;
        resumeRestVideo();
        fadeTo(itemImage, 0);
        hideSlide();
        hideSong();
        hideClock();
        stopItemMedia();
        if (!isPresenting()) {
            hideInfo();
        }
        updateRestClock();
    }

    void setItemPaused(boolean paused) {
        if (item.kind() == ItemKind.VIDEO || item.kind() == ItemKind.AUDIO || item.kind() == ItemKind.SONG) {
            itemEngine.setPaused(paused);
        }
    }

    long itemPositionMs() {
        return itemEngine.positionMs();
    }

    long itemDurationMs() {
        return itemEngine.durationMs();
    }

    private void enterItem(Item next) {
        item = next;
        hideInfo();
        hideClock();
        updateRestClock();
    }

    private void itemMediaEnded() {
        if (item.kind() == ItemKind.VIDEO || item.kind() == ItemKind.AUDIO
                || (item.kind() == ItemKind.SONG && item.file() != null)) {
            showRest();
            onItemEnded.run();
        }
    }

    /** Fades out the item video and its sound, then stops the item engine. */
    private void stopItemMedia() {
        rampVolume(0);
        FadeTransition t = fadeTo(itemVideo, 0);
        t.setOnFinished(e -> {
            fades.remove(itemVideo, t);
            if (item.kind() != ItemKind.VIDEO && item.kind() != ItemKind.AUDIO
                    && !(item.kind() == ItemKind.SONG && item.file() != null)) {
                itemEngine.stop();
            }
        });
    }

    private void resumeRestVideo() {
        if (rest.kind() == RestKind.VIDEO) {
            restEngine.setPaused(false);
        }
    }

    private void hideSong() {
        FadeTransition t = fadeTo(song, 0);
        t.setOnFinished(e -> {
            fades.remove(song, t);
            if (item.kind() != ItemKind.SONG) {
                song.setVisible(false);
            }
        });
    }

    private void hideSlide() {
        FadeTransition t = fadeTo(slide, 0);
        t.setOnFinished(e -> {
            fades.remove(slide, t);
            if (item.kind() != ItemKind.SLIDE) {
                slide.setVisible(false);
            }
        });
    }

    /** Media channel of the mixer (master × media, 0..1): scales videos, audio files and songs. */
    void setMediaGain(double gain) {
        mediaGain = Math.clamp(gain, 0, 1);
        applyItemVolume();
    }

    private void applyItemVolume() {
        if (itemEngine != null) {
            itemEngine.setVolume(itemVolume.get() * mediaGain);
        }
    }

    private void rampVolume(double target) {
        if (volumeRamp != null) {
            volumeRamp.stop();
        }
        volumeRamp = new Timeline(new KeyFrame(fade, new KeyValue(itemVolume, target)));
        volumeRamp.play();
    }

    // ------------------------------------------------------------------ information panel

    /** Information panel over black (connection state, pairing); stops anything presented. */
    void showInfo(String title, String code, String detail) {
        if (item.kind() != ItemKind.NONE) {
            showRest();
        }
        clearRest();
        infoTitle.setText(title);
        infoCode.setText(code == null ? "" : code);
        infoCode.setManaged(code != null);
        infoCode.setVisible(code != null);
        infoDetail.setText(detail == null ? "" : detail);
        info.setVisible(true);
        fadeTo(info, 1);
        infoShown = true;
        updateRestClock();
    }

    private void hideInfo() {
        infoShown = false;
        updateRestClock();
        FadeTransition t = fadeTo(info, 0);
        t.setOnFinished(e -> {
            fades.remove(info, t);
            info.setVisible(false);
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Fades a node, replacing any fade still running on it, so the end of an earlier fade (and
     * its onFinished) never hides a node that was shown again in the meantime.
     */
    private FadeTransition fadeTo(Node node, double opacity) {
        FadeTransition running = fades.remove(node);
        if (running != null) {
            running.stop(); // stop() does not run onFinished
        }
        FadeTransition t = new FadeTransition(fade, node);
        t.setToValue(opacity);
        t.setOnFinished(e -> fades.remove(node, t));
        fades.put(node, t);
        t.play();
        return t;
    }

    private void scaleText(double height) {
        slideTitle.setFont(Font.font("System", FontWeight.BOLD, Math.max(24, height * 0.085)));
        slideBody.setFont(Font.font("System", FontWeight.NORMAL, Math.max(18, height * 0.055)));
        songLines.setFont(Font.font("System", FontWeight.BOLD, Math.max(26, height * 0.075)));
        songCue.setFont(Font.font("System", FontWeight.NORMAL, Math.max(16, height * 0.04)));
        infoTitle.setFont(Font.font("System", FontWeight.LIGHT, Math.max(20, height * 0.06)));
        infoCode.setFont(Font.font("Monospaced", FontWeight.BOLD, Math.max(40, height * 0.16)));
        infoDetail.setFont(Font.font("System", FontWeight.NORMAL, Math.max(14, height * 0.032)));
        clockLabel.setFont(Font.font("System", FontWeight.NORMAL, Math.max(20, height * 0.06)));
        clockValue.setFont(Font.font("Monospaced", FontWeight.BOLD, Math.max(60, height * 0.26)));
        clockSub.setFont(Font.font("System", FontWeight.NORMAL, Math.max(16, height * 0.045)));
        restClock.setFont(Font.font("System", FontWeight.BOLD, Math.max(48, height * 0.2)));
    }

    /** Scales the image to cover the whole output, like CSS object-fit: cover. */
    private void cover(ImageView view) {
        Image image = view.getImage();
        if (image == null || image.getWidth() <= 0 || root.getWidth() <= 0) {
            return;
        }
        double scale = Math.max(root.getWidth() / image.getWidth(), root.getHeight() / image.getHeight());
        view.setFitWidth(image.getWidth() * scale);
        view.setFitHeight(image.getHeight() * scale);
    }

    private Node bindFit(Node node) {
        if (node instanceof ImageView iv) {
            iv.fitWidthProperty().bind(root.widthProperty());
            iv.fitHeightProperty().bind(root.heightProperty());
        }
        return node;
    }

    private static ImageView fitted(ImageView view) {
        view.setPreserveRatio(true);
        return view;
    }

    void close() {
        if (restEngine != null) {
            restEngine.close();
            itemEngine.close();
        }
    }

    private static Label label(FontWeight weight, Color color) {
        Label label = new Label();
        label.setFont(Font.font("System", weight, 32));
        label.setTextFill(color);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setWrapText(true);
        return label;
    }
}

package webpresenter.player;

import java.nio.file.Path;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
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
 * The output window on the chosen display. Layers, bottom to top: video, rest image, and an
 * information panel (connection state, pairing code) that is only shown when nothing is being presented.
 * All methods must be called on the JavaFX thread.
 */
final class PresentationWindow {

    private static final System.Logger LOG = System.getLogger(PresentationWindow.class.getName());

    private final Stage stage;
    private final boolean windowed;
    private final StackPane root = new StackPane();
    private final ImageView restImage = new ImageView();
    private final VBox info = new VBox(18);
    private final Label infoTitle = label(44, FontWeight.LIGHT, Color.gray(0.85));
    private final Label infoCode = label(120, FontWeight.BOLD, Color.WHITE);
    private final Label infoDetail = label(24, FontWeight.NORMAL, Color.gray(0.6));

    private VideoEngine engine;
    private Node videoView;
    private Screen screen;
    private Rest rest = Rest.NONE;
    private Duration fade = Duration.millis(Message.Transition.DEFAULT.durationMs());
    private final java.util.Map<Node, FadeTransition> fades = new java.util.HashMap<>();

    private enum RestKind { NONE, IMAGE, VIDEO }

    private record Rest(RestKind kind, Path file) {
        static final Rest NONE = new Rest(RestKind.NONE, null);
    }

    PresentationWindow(Stage stage, boolean windowed) {
        this.stage = stage;
        this.windowed = windowed;

        root.setStyle("-fx-background-color: black;");
        restImage.setPreserveRatio(true);
        restImage.fitWidthProperty().bind(root.widthProperty());
        restImage.fitHeightProperty().bind(root.heightProperty());
        restImage.setOpacity(0);

        infoCode.setStyle("-fx-font-family: 'Monospaced';");
        info.setAlignment(Pos.CENTER);
        info.setPadding(new Insets(48));
        info.getChildren().addAll(infoTitle, infoCode, infoDetail);
        root.getChildren().addAll(restImage, info);

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
    }

    Scene scene() {
        return stage.getScene();
    }

    void setTransition(Message.Transition transition) {
        fade = Duration.millis(transition.type() == Message.TransitionType.CUT ? 1 : Math.max(1, transition.durationMs()));
    }

    /** Moves the window to a display, recreating the engine if the output resolution changes. */
    void moveTo(int displayIndex) {
        List<Screen> screens = Screen.getScreens();
        Screen target = screens.get(displayIndex >= 0 && displayIndex < screens.size() ? displayIndex : 0);
        if (target.equals(screen) && engine != null) {
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
        replaceEngine((int) Math.round(width * target.getOutputScaleX()),
                (int) Math.round(height * target.getOutputScaleY()));
        LOG.log(System.Logger.Level.INFO, "presenting on display {0} {1}", screens.indexOf(target), bounds);
    }

    private void replaceEngine(int width, int height) {
        if (engine != null) {
            root.getChildren().remove(videoView);
            engine.close();
        }
        engine = new MpvEngine(width, height);
        engine.setVolume(0); // rest screens are visual only
        videoView = engine.view();
        if (videoView instanceof ImageView iv) {
            iv.fitWidthProperty().bind(root.widthProperty());
            iv.fitHeightProperty().bind(root.heightProperty());
        }
        videoView.setOpacity(rest.kind() == RestKind.VIDEO ? 1 : 0);
        root.getChildren().addFirst(videoView);
        if (rest.kind() == RestKind.VIDEO) {
            engine.play(rest.file(), true);
        }
    }

    // ------------------------------------------------------------------ what is shown

    boolean isPresenting() {
        return rest.kind() != RestKind.NONE;
    }

    void showRestImage(Path file) {
        Rest previous = rest;
        rest = new Rest(RestKind.IMAGE, file);
        restImage.setImage(new Image(file.toUri().toString()));
        hideInfo();
        fadeTo(restImage, 1);
        if (previous.kind() == RestKind.VIDEO) {
            stopVideoAfterFade();
        }
    }

    void showRestVideo(Path file) {
        Rest previous = rest;
        rest = new Rest(RestKind.VIDEO, file);
        hideInfo();
        engine.play(file, true);
        fadeTo(videoView, 1);
        if (previous.kind() == RestKind.IMAGE) {
            fadeTo(restImage, 0);
        }
    }

    /** No rest screen configured: black output. */
    void showBlack() {
        Rest previous = rest;
        rest = Rest.NONE;
        hideInfo();
        fadeTo(restImage, 0);
        if (previous.kind() == RestKind.VIDEO) {
            stopVideoAfterFade();
        }
    }

    private void stopVideoAfterFade() {
        Node view = videoView;
        FadeTransition t = fadeTo(view, 0);
        t.setOnFinished(e -> {
            fades.remove(view, t);
            if (rest.kind() != RestKind.VIDEO) {
                engine.stop();
            }
        });
    }

    /** Information panel over a black background (also stops any rest screen). */
    void showInfo(String title, String code, String detail) {
        if (isPresenting()) {
            showBlack();
        }
        infoTitle.setText(title);
        infoCode.setText(code == null ? "" : code);
        infoCode.setManaged(code != null);
        infoCode.setVisible(code != null);
        infoDetail.setText(detail == null ? "" : detail);
        info.setVisible(true);
        fadeTo(info, 1);
    }

    private void hideInfo() {
        FadeTransition t = fadeTo(info, 0);
        t.setOnFinished(e -> {
            fades.remove(info, t);
            info.setVisible(false);
        });
    }

    /**
     * Fades a node, replacing any fade still running on it. Without this, the end of an earlier
     * fade-out (and its onFinished) could hide a node that was shown again in the meantime.
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

    void close() {
        if (engine != null) {
            engine.close();
        }
    }

    private static Label label(double size, FontWeight weight, Color color) {
        Label label = new Label();
        label.setFont(Font.font("System", weight, size));
        label.setTextFill(color);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setWrapText(true);
        return label;
    }
}

package webpresenter.player;

import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import webpresenter.player.engine.VideoEngine;
import webpresenter.player.engine.mpv.MpvEngine;

/**
 * MediaPlayer presentation window. For now it only opens the output screen with the playback
 * engine loaded and a waiting message; the server connection arrives in M3.
 */
public final class PlayerApp extends Application {

    private static final System.Logger LOG = System.getLogger(PlayerApp.class.getName());

    private VideoEngine engine;

    @Override
    public void start(Stage stage) {
        PlayerConfig config = PlayerConfig.from(getParameters().getRaw());
        List<Screen> screens = Screen.getScreens();
        Screen screen = screens.get(Math.min(config.screen(), screens.size() - 1));
        Rectangle2D bounds = screen.getBounds();
        double width = config.windowed() ? 1280 : bounds.getWidth();
        double height = config.windowed() ? 720 : bounds.getHeight();

        engine = new MpvEngine(
                (int) Math.round(width * screen.getOutputScaleX()),
                (int) Math.round(height * screen.getOutputScaleY()));

        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: black;");
        ImageView video = (ImageView) engine.view();
        video.fitWidthProperty().bind(root.widthProperty());
        video.fitHeightProperty().bind(root.heightProperty());

        Label waiting = new Label("Aguardando conexão com o servidor\n" + config.serverUrl());
        waiting.setFont(Font.font("System", FontWeight.LIGHT, 36));
        waiting.setTextFill(Color.gray(0.7));
        waiting.setStyle("-fx-text-alignment: center;");
        root.getChildren().addAll(video, waiting);

        Scene scene = new Scene(root, width, height, Color.BLACK);
        scene.setCursor(config.windowed() ? Cursor.DEFAULT : Cursor.NONE);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                Platform.exit();
            }
        });

        stage.setTitle("webpresenter player");
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        if (!config.windowed()) {
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setFullScreenExitHint("");
            stage.setFullScreen(true);
        }
        stage.setScene(scene);
        stage.show();
        LOG.log(System.Logger.Level.INFO, "player started on screen {0} ({1}), engine {2}, server {3}",
                config.screen(), bounds, engine.name(), config.serverUrl());
    }

    @Override
    public void stop() {
        if (engine != null) {
            engine.close();
        }
    }
}

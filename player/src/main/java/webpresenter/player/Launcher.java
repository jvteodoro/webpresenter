package webpresenter.player;

/** Entry point that does not extend Application, so JavaFX can be loaded from the classpath. */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        javafx.application.Application.launch(PlayerApp.class, args);
    }
}

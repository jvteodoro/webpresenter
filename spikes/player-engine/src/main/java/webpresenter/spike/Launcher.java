package webpresenter.spike;

/** Entry point that does not extend Application, so JavaFX can be loaded from the classpath. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        javafx.application.Application.launch(SpikeApp.class, args);
    }
}

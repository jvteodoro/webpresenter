package webpresenter.player;

import java.util.function.DoubleConsumer;
import javafx.animation.Interpolator;
import javafx.animation.Transition;
import javafx.util.Duration;

/** Linear fades on the JavaFX animation timer. */
final class FxFader implements BackgroundMusicPlayer.Fader {

    private Transition current;

    @Override
    public void fade(double from, double to, long durationMs, DoubleConsumer step, Runnable done) {
        cancel();
        if (durationMs <= 0 || from == to) {
            step.accept(to);
            done.run();
            return;
        }
        Transition fade = new Transition() {
            {
                setCycleDuration(Duration.millis(durationMs));
                setInterpolator(Interpolator.LINEAR);
            }

            @Override
            protected void interpolate(double fraction) {
                step.accept(from + (to - from) * fraction);
            }
        };
        fade.setOnFinished(e -> {
            current = null;
            done.run();
        });
        current = fade;
        fade.play();
    }

    @Override
    public void cancel() {
        if (current != null) {
            current.setOnFinished(null);
            current.stop();
            current = null;
        }
    }
}

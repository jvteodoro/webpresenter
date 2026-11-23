package webpresenter.server.songs;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How slides look: background image, text colour and text size (percent of the default). Every
 * field is optional and inherits: an event item's style over its event's, over the song's own.
 *
 * @param backgroundMediaId library image behind the text, or null
 * @param textColor "#rrggbb", or null
 * @param textScale 50..200 (100 = default size), or null
 */
public record SlideStyle(UUID backgroundMediaId, String textColor, Integer textScale) {

    public static final SlideStyle NONE = new SlideStyle(null, null, null);
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** This style's fields where set, the base's otherwise. */
    public SlideStyle over(SlideStyle base) {
        if (base == null) {
            return this;
        }
        return new SlideStyle(backgroundMediaId != null ? backgroundMediaId : base.backgroundMediaId,
                textColor != null ? textColor : base.textColor, textScale != null ? textScale : base.textScale);
    }

    public boolean isEmpty() {
        return backgroundMediaId == null && textColor == null && textScale == null;
    }

    /** @throws IllegalArgumentException for a malformed colour or a size out of range */
    public SlideStyle validate() {
        if (textColor != null && !COLOR.matcher(textColor).matches()) {
            throw new IllegalArgumentException("textColor must be #rrggbb");
        }
        if (textScale != null && (textScale < 50 || textScale > 200)) {
            throw new IllegalArgumentException("textScale must be between 50 and 200");
        }
        return this;
    }
}

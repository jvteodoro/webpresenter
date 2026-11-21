package webpresenter.server.songs;

import java.util.List;

/**
 * When each slide of the slide sequence starts in one recording.
 *
 * @param lyricsVersion lyrics version the cue indexes refer to (set by the server); a mismatch means "needs review"
 * @param source IMPORT, MANUAL or PIPELINE
 * @param reviewed false for automatic results nobody checked yet
 */
public record Timing(Integer lyricsVersion, Source source, Boolean reviewed, List<Cue> cues) {

    public record Cue(int slide, long startMs) {}

    public Timing {
        cues = cues == null ? List.of() : List.copyOf(cues);
        reviewed = Boolean.TRUE.equals(reviewed);
    }

    /** @throws IllegalArgumentException if cues are out of range or not in time order */
    public Timing validate(int slideCount) {
        long previous = -1;
        int previousSlide = -1;
        for (Cue cue : cues) {
            if (cue.slide() < 0 || cue.slide() >= slideCount) {
                throw new IllegalArgumentException("cue for slide " + cue.slide() + " outside 0.." + (slideCount - 1));
            }
            if (cue.slide() <= previousSlide || cue.startMs() < previous) {
                throw new IllegalArgumentException("cues must follow the slide sequence in time order");
            }
            previous = cue.startMs();
            previousSlide = cue.slide();
        }
        return this;
    }
}

package webpresenter.server.songs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lyrics of a song: unique parts (verse, chorus…) made of slides, and the order in which parts are
 * sung (repeats allowed). The expanded {@link #slideSequence() slide sequence} is what players show
 * and what timing refers to.
 */
public record Lyrics(List<Part> parts, List<String> order) {

    public enum PartKind { VERSE, CHORUS, PRE_CHORUS, BRIDGE, INTRO, OUTRO, TAG, INSTRUMENTAL, OTHER }

    /** A unique block of the song; INSTRUMENTAL parts have one slide without lines. */
    public record Part(String id, PartKind kind, String label, List<Slide> slides) {
        public Part {
            slides = slides == null ? List.of() : List.copyOf(slides);
        }
    }

    /** Lines shown together, with an optional cue such as "Homens:". */
    public record Slide(List<String> lines, String cue) {
        public Slide {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One entry of the slide sequence: which part and slide, and its content. */
    public record SequenceSlide(int index, String partId, PartKind kind, String partLabel, int slideInPart,
            List<String> lines, String cue) {}

    public static final Lyrics EMPTY = new Lyrics(List.of(), List.of());

    public Lyrics {
        parts = parts == null ? List.of() : List.copyOf(parts);
        order = order == null ? List.of() : List.copyOf(order);
    }

    /** @throws IllegalArgumentException describing the first structural problem */
    public Lyrics validate() {
        Set<String> ids = new HashSet<>();
        for (Part part : parts) {
            if (part.id() == null || part.id().isBlank() || !ids.add(part.id())) {
                throw new IllegalArgumentException("part ids must be present and unique: " + part.id());
            }
            if (part.kind() == null) {
                throw new IllegalArgumentException("part " + part.id() + " has no kind");
            }
            if (part.slides().isEmpty()) {
                throw new IllegalArgumentException("part " + part.id() + " has no slides");
            }
        }
        for (String id : order) {
            if (!ids.contains(id)) {
                throw new IllegalArgumentException("order references unknown part " + id);
            }
        }
        return this;
    }

    /** Parts in singing order, expanded into individual slides. */
    public List<SequenceSlide> slideSequence() {
        Map<String, Part> byId = parts.stream().collect(Collectors.toMap(Part::id, Function.identity()));
        List<SequenceSlide> sequence = new ArrayList<>();
        for (String id : order) {
            Part part = byId.get(id);
            for (int i = 0; i < part.slides().size(); i++) {
                Slide slide = part.slides().get(i);
                sequence.add(new SequenceSlide(sequence.size(), part.id(), part.kind(), part.label(), i,
                        slide.lines(), slide.cue()));
            }
        }
        return sequence;
    }
}

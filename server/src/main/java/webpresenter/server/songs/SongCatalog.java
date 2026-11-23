package webpresenter.server.songs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to songs for other modules (planning, live presentation). */
public interface SongCatalog {

    Optional<SongPlan> find(UUID songId);

    /** What presenting a song needs: the slide sequence, recordings with usable timing, background. */
    /** @param style the song's own slide appearance (its background, text colour and size) */
    record SongPlan(UUID id, String title, List<PlanSlide> slides, List<PlanRecording> recordings,
            UUID backgroundMediaId, SlideStyle style) {

        public Optional<PlanRecording> recording(UUID recordingId) {
            return recordings.stream().filter(r -> r.id().equals(recordingId)).findFirst();
        }
    }

    record PlanSlide(String partLabel, boolean instrumental, List<String> lines, String cue) {}

    /**
     * @param cueStartMs start of each slide in this recording, or null for slides without a cue;
     *     empty when the timing is missing or no longer matches the lyrics (the operator advances)
     */
    record PlanRecording(UUID id, RecordingKind kind, UUID mediaId, List<Long> cueStartMs) {}
}

package webpresenter.server.songs;

import java.util.UUID;

/** What the processing pipeline writes into songs (other modules: jobs). */
public interface SongPipeline {

    /**
     * Adds a PLAYBACK recording generated from another recording of the same song (vocals removed),
     * with that recording's timing copied (same timeline), marked {@code PIPELINE} and not reviewed.
     *
     * @return the new recording's id
     */
    UUID addGeneratedPlayback(UUID songId, UUID sourceRecordingId, UUID mediaId);

    /**
     * Registers the tracks separated from a recording (same timeline: its timing is copied, marked
     * {@code PIPELINE} and not reviewed): a PLAYBACK (everything but the vocals) when given, and a
     * STEM recording per part. Tracks the pipeline separated earlier from the same recording, for the
     * same parts (or an earlier playback), are replaced; their media are returned so the caller can
     * discard them.
     *
     * @param playbackMediaId the generated playback, or null
     */
    SeparatedTracks addSeparatedTracks(UUID songId, UUID sourceRecordingId, UUID playbackMediaId,
            java.util.Map<StemPart, UUID> stems);

    /** @param replacedMedia media of the tracks that were replaced (no longer used by this song) */
    record SeparatedTracks(java.util.List<UUID> recordingIds, java.util.List<UUID> replacedMedia) {}

    /** The part a STEM recording holds (null for other kinds), if the song and recording exist. */
    java.util.Optional<StemPart> recordingPart(UUID songId, UUID recordingId);

    /**
     * Creates a draft song from downloaded audio: the title, an instrumental placeholder for the
     * lyrics (to be written or transcribed later) and the audio as its SUNG recording.
     *
     * @return the new song's id
     */
    UUID createFromRecording(String title, UUID mediaId);

    /**
     * Applies lyrics and timing found from a sung recording: new lyrics (when transcribed; null
     * keeps the song's) and the start of each slide, on that recording and on the PLAYBACK
     * recordings generated from the song by the pipeline (same timeline). The song becomes a draft
     * and the timing is marked not reviewed.
     *
     * @param lyricsJson {"parts": …, "order": …} or null
     * @return the number of slides in the sequence
     */
    int applyGeneratedLyrics(UUID songId, UUID recordingId, String lyricsJson, java.util.List<GeneratedCue> cues);

    record GeneratedCue(int slide, long startMs) {}

    /**
     * The text of each slide of the song in singing order ("" for instrumental slides), or empty
     * when the song has no lyrics yet (only instrumental placeholders).
     */
    java.util.List<String> slideTexts(UUID songId);

    /** The media of a recording, if the song and recording exist. */
    java.util.Optional<UUID> recordingMedia(UUID songId, UUID recordingId);
}

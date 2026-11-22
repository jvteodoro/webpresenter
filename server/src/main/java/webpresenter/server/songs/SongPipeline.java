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
     * Creates a draft song from downloaded audio: the title, an instrumental placeholder for the
     * lyrics (to be written or transcribed later) and the audio as its SUNG recording.
     *
     * @return the new song's id
     */
    UUID createFromRecording(String title, UUID mediaId);

    /** The media of a recording, if the song and recording exist. */
    java.util.Optional<UUID> recordingMedia(UUID songId, UUID recordingId);
}

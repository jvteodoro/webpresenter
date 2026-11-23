package webpresenter.server.jobs;

enum JobType {
    /** Downloads audio or video with yt-dlp into the library (optionally as a new song). */
    YOUTUBE_IMPORT,
    /**
     * Separates a song recording with Demucs: the vocals, drums, bass and other instruments as STEM
     * recordings, and a PLAYBACK (everything but the vocals).
     */
    SEPARATE_PLAYBACK,
    /** Lyrics (when the song has none) and slide times from a sung recording, with Whisper. */
    TRANSCRIBE_LYRICS,
    /** Separates the choir voices (soprano, alto, tenor, bass…) of a vocals track into STEM recordings. */
    SEPARATE_VOICES;

    /** Jobs that need a lot of computation (a GPU helps); see {@link JobDispatcher}. */
    boolean heavy() {
        return this != YOUTUBE_IMPORT;
    }
}

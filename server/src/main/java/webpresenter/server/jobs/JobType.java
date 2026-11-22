package webpresenter.server.jobs;

enum JobType {
    /** Downloads audio or video with yt-dlp into the library (optionally as a new song). */
    YOUTUBE_IMPORT,
    /** Removes the vocals of a song recording with Demucs, adding a PLAYBACK recording. */
    SEPARATE_PLAYBACK
}

package webpresenter.server.library;

/** Why a file is in the library; the library page shows GENERAL files by default. */
public enum MediaPurpose {
    /** Everyday media chosen by people (videos, images, audio for events). */
    GENERAL,
    /** Recording of a song (sung or playback). */
    SONG_AUDIO,
    /** Background image or video of song slides. */
    SONG_BACKGROUND
}

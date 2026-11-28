package webpresenter.server.library;

/** Why a file is in the library; the library page shows GENERAL files by default. */
public enum MediaPurpose {
    /** Everyday media chosen by people (videos, images, audio for events). */
    GENERAL,
    /** Recording of a song (sung or playback). */
    SONG_AUDIO,
    /** Background image or video of song slides. */
    SONG_BACKGROUND,
    /** Music played softly when nothing else sounds (rest screen, slides). */
    BACKGROUND_MUSIC,
    /** Short sound used by timers and cues. */
    SOUND_EFFECT,
    /** Cover image of an album or playlist. */
    COVER,
    /** Videos for events downloaded from YouTube (e.g. a recurring show), kept apart from everyday media. */
    MISC,
    /** A singer's video or audio, played as it was provided (never converted). */
    MUSICAL_MESSAGE
}

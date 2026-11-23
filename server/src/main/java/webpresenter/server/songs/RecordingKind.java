package webpresenter.server.songs;

public enum RecordingKind {
    /** With vocals. */
    SUNG,
    /** Instrumental / backing track. */
    PLAYBACK,
    OTHER,
    /** A separated track (one instrument or one voice) of another recording; see {@link StemPart}. */
    STEM
}

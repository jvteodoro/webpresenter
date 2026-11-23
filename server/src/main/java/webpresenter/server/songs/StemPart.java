package webpresenter.server.songs;

/** What a separated track (recording of kind {@code STEM}) holds. */
public enum StemPart {
    // Demucs (htdemucs): the song's instruments and its voice.
    VOCALS,
    DRUMS,
    BASS,
    OTHER,
    // Choir voices, separated from the vocals.
    LEAD,
    SOPRANO,
    ALTO,
    TENOR,
    BASS_VOICE,
    VOCAL_PERCUSSION;

    /** Choir voices (separated from the vocals), as opposed to the instrument stems. */
    public boolean voice() {
        return ordinal() >= LEAD.ordinal();
    }
}

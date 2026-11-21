package webpresenter.server.songs;

/** Where a piece of song data came from. */
public enum Source {
    /** Typed or uploaded by a person. */
    MANUAL,
    /** Imported from another tool (origin recorded on the song). */
    IMPORT,
    /** Produced by the automatic pipeline (needs review). */
    PIPELINE
}

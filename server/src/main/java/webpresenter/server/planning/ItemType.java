package webpresenter.server.planning;

public enum ItemType {
    /** A library file (video, audio or image). */
    MEDIA,
    /** Text rendered by the player. */
    SLIDE,
    /** A song: lyrics over a background, with or without one of its recordings. */
    SONG,
    /**
     * Not presented: starts a scene, the group of the items after it (until the next scene), with
     * a name, planned times and background music.
     */
    SCENE
}

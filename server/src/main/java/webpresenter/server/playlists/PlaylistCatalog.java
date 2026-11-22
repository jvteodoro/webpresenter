package webpresenter.server.playlists;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Background music for other modules: validating a source and expanding it into tracks. */
public interface PlaylistCatalog {

    /**
     * @throws IllegalArgumentException if the playlist or media does not exist, or the media has no sound
     */
    void validate(BackgroundSource source);

    /**
     * The tracks to play for a source; empty for silence, a missing playlist or an empty one.
     * Songs play their chosen recording (or the sung one, else the first); songs without
     * recordings are skipped.
     */
    Optional<Tracks> tracks(BackgroundSource source);

    /** Display name of a source ("Playlist Recepção", the track title, "Silêncio"). */
    String describe(BackgroundSource source);

    /**
     * @param key stable identity of the source ("playlist:&lt;id&gt;", "media:&lt;id&gt;"): players keep
     *     the music going between items with the same key
     */
    record Tracks(String key, List<UUID> mediaIds, boolean shuffle) {}

    /** Published when a playlist's content changes (players with it as background need the new tracks). */
    record PlaylistChanged(UUID playlistId) {}
}

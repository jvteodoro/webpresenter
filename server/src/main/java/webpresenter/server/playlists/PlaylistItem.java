package webpresenter.server.playlists;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** A song (with an optional recording choice) or a library media file. */
@Entity
@Table(name = "playlist_item")
class PlaylistItem {

    enum Type { SONG, MEDIA }

    /** What an item is made of, as given by the API. */
    record Spec(Type type, UUID songId, UUID recordingId, UUID mediaId) {}

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id")
    private Playlist playlist;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "song_id")
    private UUID songId;

    @Column(name = "recording_id")
    private UUID recordingId;

    @Column(name = "media_id")
    private UUID mediaId;

    protected PlaylistItem() {}

    PlaylistItem(Playlist playlist, int position, Spec spec) {
        this.id = UUID.randomUUID();
        this.playlist = playlist;
        this.position = position;
        this.type = spec.type();
        this.songId = spec.type() == Type.SONG ? spec.songId() : null;
        this.recordingId = spec.type() == Type.SONG ? spec.recordingId() : null;
        this.mediaId = spec.type() == Type.MEDIA ? spec.mediaId() : null;
    }

    UUID id() { return id; }
    Type type() { return type; }
    UUID songId() { return songId; }
    UUID recordingId() { return recordingId; }
    UUID mediaId() { return mediaId; }
}

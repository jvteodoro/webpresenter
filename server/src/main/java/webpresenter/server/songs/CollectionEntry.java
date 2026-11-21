package webpresenter.server.songs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** A song in a collection, with its number there (hymn number, album track). */
@Entity
@Table(name = "song_collection_entry")
class CollectionEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id")
    private Song song;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "collection_id")
    private SongCollection collection;

    @Column(name = "number")
    private String number;

    protected CollectionEntry() {}

    CollectionEntry(Song song, SongCollection collection, String number) {
        this.id = UUID.randomUUID();
        this.song = song;
        this.collection = collection;
        this.number = number;
    }

    SongCollection collection() { return collection; }
    String number() { return number; }
}

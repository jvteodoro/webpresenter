package webpresenter.server.playlists;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

/**
 * Where background music comes from: a playlist, one library track, or explicit silence. Where a
 * source is optional, null means "inherit" (item → scene → event → platform).
 *
 * @param ref the playlist or media id; null for {@link Kind#SILENCE}
 */
@Embeddable
public record BackgroundSource(@Enumerated(EnumType.STRING) @Column(name = "kind") Kind kind,
        @Column(name = "ref") UUID ref) {

    public enum Kind { PLAYLIST, MEDIA, SILENCE }

    public static BackgroundSource silence() {
        return new BackgroundSource(Kind.SILENCE, null);
    }

    public static BackgroundSource playlist(UUID id) {
        return new BackgroundSource(Kind.PLAYLIST, id);
    }

    public static BackgroundSource media(UUID id) {
        return new BackgroundSource(Kind.MEDIA, id);
    }
}

package webpresenter.server.playlists;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    List<Playlist> findAllByOrderByNameAsc();

    @Query("select distinct i.playlist from PlaylistItem i where i.mediaId = :mediaId")
    List<Playlist> findAllUsingMedia(UUID mediaId);
}

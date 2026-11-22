package webpresenter.server.songs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SongCollectionRepository extends JpaRepository<SongCollection, UUID> {

    Optional<SongCollection> findBySlug(String slug);

    List<SongCollection> findAllByOrderBySortOrderAscNameAsc();

    List<SongCollection> findAllByCoverMediaId(UUID coverMediaId);
}

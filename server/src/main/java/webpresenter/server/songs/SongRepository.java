package webpresenter.server.songs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SongRepository extends JpaRepository<Song, UUID> {

    Optional<Song> findByOriginAndExternalId(String origin, String externalId);

    @Query("""
            select s from Song s
            where (:term = '' or s.searchText like concat('%', :term, '%'))
              and (:collection is null or exists (
                    select 1 from CollectionEntry e where e.song = s and e.collection.slug = :collection))
            order by s.title""")
    List<Song> search(String term, String collection, Pageable page);

    @Query("""
            select count(s) from Song s
            where (:term = '' or s.searchText like concat('%', :term, '%'))
              and (:collection is null or exists (
                    select 1 from CollectionEntry e where e.song = s and e.collection.slug = :collection))""")
    long countSearch(String term, String collection);

    @Query("select distinct r.song from Recording r where r.mediaId = :mediaId")
    List<Song> findAllWithRecordingMedia(UUID mediaId);

    List<Song> findAllByBackgroundMediaId(UUID mediaId);
}

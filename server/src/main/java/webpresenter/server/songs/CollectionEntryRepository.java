package webpresenter.server.songs;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CollectionEntryRepository extends JpaRepository<CollectionEntry, UUID> {

    @Query("select e from CollectionEntry e join fetch e.song where e.collection.id = :collectionId")
    List<CollectionEntry> findAllByCollection(UUID collectionId);

    @Query("select e.collection.id, count(e) from CollectionEntry e group by e.collection.id")
    List<Object[]> countByCollection();
}

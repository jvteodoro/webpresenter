package webpresenter.server.videos;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VideoSourceRepository extends JpaRepository<VideoSource, UUID> {

    List<VideoSource> findAllByOrderByNameAsc();

    List<VideoSource> findAllByEnabledTrue();
}

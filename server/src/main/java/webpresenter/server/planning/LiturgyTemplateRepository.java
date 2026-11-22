package webpresenter.server.planning;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LiturgyTemplateRepository extends JpaRepository<LiturgyTemplate, UUID> {

    List<LiturgyTemplate> findAllByOrderByNameAsc();
}

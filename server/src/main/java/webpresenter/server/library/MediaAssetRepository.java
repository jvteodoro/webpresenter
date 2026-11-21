package webpresenter.server.library;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findAllByOrderByCreatedAtDesc();

    List<MediaAsset> findAllByPurposeOrderByCreatedAtDesc(MediaPurpose purpose);

    java.util.Optional<MediaAsset> findFirstBySha256AndSizeBytes(String sha256, long sizeBytes);
}

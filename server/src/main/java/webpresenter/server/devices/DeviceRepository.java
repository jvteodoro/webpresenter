package webpresenter.server.devices;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findAllByOrderByStatusDescNameAsc();

    List<Device> findAllByRestMediaId(UUID mediaId);

    List<Device> findAllByStatus(DeviceStatus status);
}

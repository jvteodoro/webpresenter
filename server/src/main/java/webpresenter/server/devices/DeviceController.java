package webpresenter.server.devices;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Device management for operators. */
@RestController
@RequestMapping("/api/devices")
@PreAuthorize("hasAnyRole('ADMIN', 'AV_TECHNICIAN')")
class DeviceController {

    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @GetMapping
    List<DeviceService.DeviceView> list() {
        return devices.list();
    }

    @PostMapping("/{id}/approve")
    DeviceService.DeviceView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request,
            Authentication user) {
        return devices.approve(id, request == null ? null : request.name(), user.getName());
    }

    @PutMapping("/{id}/settings")
    DeviceService.DeviceView configure(@PathVariable UUID id, @Valid @RequestBody SettingsRequest request) {
        return devices.configure(id, request.name(), request.displayIndex(), request.restMediaId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id) {
        devices.remove(id);
    }

    record ApproveRequest(@Size(max = 100) String name) {}

    record SettingsRequest(@Size(max = 100) String name, @Min(0) int displayIndex, UUID restMediaId) {}

    @ExceptionHandler(DeviceService.DeviceNotFoundException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeviceService.DeviceStateException.class)
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DeviceService.InvalidSettingsException.class)
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}

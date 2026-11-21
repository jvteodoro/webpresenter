package webpresenter.server.devices;

public enum DeviceStatus {
    /** Connected at least once, waiting for an operator to approve it. */
    PENDING,
    /** Approved: holds a device token and receives configuration. */
    APPROVED
}

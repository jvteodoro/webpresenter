package webpresenter.server.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_setting")
class AppSetting {

    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    protected AppSetting() {}

    AppSetting(String key) {
        this.key = key;
    }

    void set(String value, String user, Instant now) {
        this.value = value;
        this.updatedBy = user;
        this.updatedAt = now;
    }

    String value() { return value; }
}

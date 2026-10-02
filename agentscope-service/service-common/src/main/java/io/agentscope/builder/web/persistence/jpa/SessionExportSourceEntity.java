package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Durable source catalogue: closed or crashed runs remain discoverable for outbox repair. */
@Entity
@Table(name = "builder_session_export_source")
public class SessionExportSourceEntity {
    @Id
    @Column(length = 64)
    public String sessionId;

    @Column(nullable = false, length = 128)
    public String userId;

    @Column(nullable = false, length = 512)
    public String agentId;

    @Column(name = "parent_session_id", length = 64)
    public String parentSessionId;

    public SessionExportSourceEntity() {}
}

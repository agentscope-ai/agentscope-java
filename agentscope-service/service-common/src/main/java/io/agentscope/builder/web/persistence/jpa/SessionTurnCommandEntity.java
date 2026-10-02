package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Durable admission independent of any HTTP or SSE subscription. */
@Entity
@Table(
        name = "builder_session_turn_command",
        indexes = {
            @Index(name = "ix_turn_command_status", columnList = "status,created_at"),
            @Index(name = "ix_turn_command_session_order", columnList = "session_id,admission_seq")
        })
public class SessionTurnCommandEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 64)
    public String sessionId;

    @Column(name = "user_id", nullable = false, length = 128)
    public String userId;

    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    public String requestJson;

    @Column(name = "continuation_json", columnDefinition = "TEXT")
    public String continuationJson;

    @Column(name = "continuation_id", length = 64)
    public String continuationId;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;

    @Column(name = "lease_until", nullable = false)
    public long leaseUntil;

    @Column(name = "worker_id", length = 64)
    public String workerId;

    @Column(name = "error_code", length = 128)
    public String errorCode;

    @Column(name = "native_start_seq", nullable = false)
    public long nativeStartSeq = -1;

    @Column(name = "admission_seq", nullable = false)
    public long admissionSeq;

    @Version public long version;

    public SessionTurnCommandEntity() {}
}

package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "builder_session_action_command",
        indexes = {
            @Index(name = "ix_action_command_delivery", columnList = "delivery_status,created_at"),
            @Index(name = "ix_action_command_turn", columnList = "turn_id,created_at")
        })
public class SessionActionCommandEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 64)
    public String turnId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String requestJson;

    @Column(nullable = false, length = 24)
    public String deliveryStatus = "applied";

    @Column(nullable = false)
    public long createdAt;

    public SessionActionCommandEntity() {}
}

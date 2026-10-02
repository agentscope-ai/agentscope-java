package io.agentscope.builder.web.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionActionCommandRepository
        extends JpaRepository<SessionActionCommandEntity, String> {
    List<SessionActionCommandEntity> findByTurnIdOrderByCreatedAtAsc(String turnId);

    List<SessionActionCommandEntity> findTop100ByDeliveryStatusOrderByCreatedAtAsc(
            String deliveryStatus);
}

package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionTurnCommandRepository
        extends JpaRepository<SessionTurnCommandEntity, String> {
    List<SessionTurnCommandEntity> findTop100ByStatusOrderByCreatedAtAsc(String status);

    List<SessionTurnCommandEntity> findTop100ByStatusInOrderByCreatedAtAsc(
            Collection<String> statuses);

    List<SessionTurnCommandEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionTurnCommandEntity> findTop100ByStatusInAndLeaseUntilLessThanOrderByCreatedAtAsc(
            Collection<String> statuses, long now);

    @Query(
            "select c from SessionTurnCommandEntity c where c.status='queued' and not exists"
                + " (select older.id from SessionTurnCommandEntity older where"
                + " older.sessionId=c.sessionId and older.admissionSeq<c.admissionSeq and"
                + " older.status in"
                + " ('queued','running','cancel_requested','requires_action','interrupted','failed'))"
                + " order by c.createdAt,c.id")
    List<SessionTurnCommandEntity> findDispatchable(org.springframework.data.domain.Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SessionTurnCommandEntity c where c.id=:id")
    Optional<SessionTurnCommandEntity> lockById(@Param("id") String id);
}

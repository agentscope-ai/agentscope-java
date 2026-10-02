package io.agentscope.builder.web.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionExportSourceRepository
        extends JpaRepository<SessionExportSourceEntity, String> {}

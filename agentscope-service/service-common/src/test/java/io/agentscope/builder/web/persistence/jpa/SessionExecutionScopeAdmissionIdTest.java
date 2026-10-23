/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.BuilderCommonTestApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * Admission ids are the coordination ids minted by {@code TurnLeaseService}: {@code
 * "<instanceId>/<uuid>"}. Long pod/host names push them past the legacy 128-char column, so the
 * column has to be wide enough to accept the same value the coordination lease already stores.
 */
@DataJpaTest
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:sessionExecutionScope;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class SessionExecutionScopeAdmissionIdTest {

    @Autowired SessionExecutionScopeRepository repository;

    @Test
    void persistsCoordinationIdLongerThanLegacyColumnWidth() {
        String admissionId = "brain-" + "a".repeat(160) + "/00000000-0000-0000-0000-000000000000";
        assertThat(admissionId.length()).isGreaterThan(128);

        SessionExecutionScopeEntity row = new SessionExecutionScopeEntity();
        row.admissionId = admissionId;
        row.sessionId = "sess_long_admission";
        row.scopeJson = "{}";
        row.leaseUntil = System.currentTimeMillis() + 60_000L;

        repository.saveAndFlush(row);

        assertThat(repository.findById(admissionId)).isPresent();
    }
}

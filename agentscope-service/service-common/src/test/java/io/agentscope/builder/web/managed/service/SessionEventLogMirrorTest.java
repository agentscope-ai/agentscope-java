/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.builder.web.managed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SessionEventLogMirrorTest {

    @Test
    void mirrorsOnlyAfterTheEventIsDurable() {
        SessionEventEntityRepository repository = mock(SessionEventEntityRepository.class);
        when(repository.maxSeq("session-1")).thenReturn(0L);
        when(repository.saveAndFlush(any(SessionEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInTransaction(null);
                        });
        List<SessionEventDto> mirrored = new ArrayList<>();
        SessionEventLog log =
                new SessionEventLog(
                        repository,
                        new ManagedJsonHelper(new ObjectMapper()),
                        transactions,
                        new DeletedSessionRegistry(),
                        mock(SessionEventNotifier.class),
                        List.of(mirrored::add),
                        30_000L);

        SessionEventDto event = log.append("session-1", "agent.message", Map.of("text", "ready"));

        assertThat(event.seq()).isEqualTo(1L);
        assertThat(mirrored).containsExactly(event);
    }
}

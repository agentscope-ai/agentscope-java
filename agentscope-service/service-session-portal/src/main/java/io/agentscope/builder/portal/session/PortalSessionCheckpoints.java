/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal.session;

import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedScope;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Checkpoint metadata comes from Hands' committed index, without a runtime replica RPC. */
@Service
public final class PortalSessionCheckpoints {
    private final PortalSessionRepository sessions;
    private final PortalSessionAccess access;
    private final ManagedJournalStore journal;

    public PortalSessionCheckpoints(
            PortalSessionRepository sessions,
            PortalSessionAccess access,
            ManagedJournalStore journal) {
        this.sessions = sessions;
        this.access = access;
        this.journal = journal;
    }

    public Mono<Map<String, Object>> list(
            String session, String after, String through, int limit, HttpHeaders headers) {
        return Mono.fromCallable(
                        () ->
                                journal.checkpoints()
                                        .list(scope(session, headers), after, through, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> metadata(
            String session, String checkpoint, HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> journal.checkpoints().metadata(scope(session, headers), checkpoint))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ManagedScope scope(String session, HttpHeaders headers) {
        access.authorize(session, headers, "read");
        var row =
                sessions.findById(session)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind))
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_capability: checkpoints");
        return new ManagedScope(row.tenant, row.namespace, row.id);
    }
}

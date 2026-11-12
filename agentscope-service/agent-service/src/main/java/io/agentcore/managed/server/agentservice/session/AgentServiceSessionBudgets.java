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
package io.agentcore.managed.server.agentservice.session;

import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Agent Service reads/updates the shared budget ledger; no execution-replica RPC is involved. */
@Service
public final class AgentServiceSessionBudgets {
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceSessionAccess access;
    private final ManagedBudgetStore budgets;

    public AgentServiceSessionBudgets(
            AgentServiceSessionRepository sessions,
            AgentServiceSessionAccess access,
            ManagedBudgetStore budgets) {
        this.sessions = sessions;
        this.access = access;
        this.budgets = budgets;
    }

    public Map<String, Object> configure(
            String id, Map<String, Object> limits, String expected, HttpHeaders headers) {
        var row = resource(id, headers, "interact");
        if (row.rootSessionId != null)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Change the root Session budget");
        if (!"active".equals(row.lifecycle))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not active");
        return budgets.configure(scope(row), limits, revision(expected));
    }

    public Mono<Map<String, Object>> get(String id, HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            var row = resource(id, headers, "read");
                            if (row.rootSessionId != null)
                                row = resource(row.rootSessionId, headers, "read");
                            return budgets.get(scope(row));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AgentServiceSessionEntity resource(String id, HttpHeaders headers, String permission) {
        access.authorize(id, headers, permission);
        var row =
                sessions.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Budget requires a Managed Session");
        return row;
    }

    private static ManagedScope scope(AgentServiceSessionEntity row) {
        return new ManagedScope(row.tenant, row.namespace, row.id);
    }

    private static Long revision(String value) {
        if (value == null) return null;
        try {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                value = value.substring(1, value.length() - 1);
            long revision = Long.parseLong(value);
            if (revision < 0) throw new NumberFormatException();
            return revision;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget revision");
        }
    }
}

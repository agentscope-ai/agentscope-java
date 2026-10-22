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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionBudgetService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/budget")
public final class AgentSessionBudgetController {
    private final DataSessionService sessions;
    private final SessionBudgetService budgets;

    public AgentSessionBudgetController(DataSessionService sessions, SessionBudgetService budgets) {
        this.sessions = sessions;
        this.budgets = budgets;
    }

    @GetMapping
    public Mono<Map<String, Object>> get(@PathVariable String session, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            budgets.reconcile(sessions.get((String) auth.getPrincipal(), session));
                            return budgets.get(session);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping
    public Mono<Map<String, Object>> configure(
            @PathVariable String session,
            @RequestBody SessionBudgetService.Budget input,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            return budgets.configure(session, input);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

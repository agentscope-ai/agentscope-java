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
package io.agentcore.managed.server.agentservice.api;

import io.agentcore.managed.server.agentservice.session.AgentServiceSessionBudgets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/sessions/{session}/budget")
public final class AgentServiceSessionBudgetController {
    private final AgentServiceSessionBudgets budgets;

    public AgentServiceSessionBudgetController(AgentServiceSessionBudgets budgets) {
        this.budgets = budgets;
    }

    @GetMapping
    public Mono<Map<String, Object>> get(
            @PathVariable String session, @RequestHeader HttpHeaders headers) {
        return budgets.get(session, headers);
    }

    @PutMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> put(
            @PathVariable String session,
            @RequestBody Map<String, Object> input,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String expected,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> budgets.configure(session, input, expected, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

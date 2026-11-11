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

import io.agentscope.builder.web.managed.AgentSessionInput;
import io.agentscope.builder.web.managed.SessionInputService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/inputs")
public final class AgentSessionInputsController {
    private final SessionInputService inputs;

    public AgentSessionInputsController(SessionInputService inputs) {
        this.inputs = inputs;
    }

    @PostMapping("/inject")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SessionInputService.Receipt> inject(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AgentSessionInput input,
            Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                inputs.accept(
                                        (String) auth.getPrincipal(),
                                        session,
                                        null,
                                        "inject",
                                        key,
                                        input))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

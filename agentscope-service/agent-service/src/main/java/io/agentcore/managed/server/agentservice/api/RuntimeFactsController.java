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
package io.agentcore.managed.server.agentservice.api;

import io.agentcore.managed.server.agentservice.session.AgentServiceRuntimeFacts;
import io.agentcore.managed.server.runtime.protocol.RuntimeFact;
import io.agentcore.managed.server.runtime.protocol.RuntimeFactReceipt;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class RuntimeFactsController {
    private final AgentServiceRuntimeFacts facts;

    public RuntimeFactsController(AgentServiceRuntimeFacts facts) {
        this.facts = facts;
    }

    @PostMapping("/api/internal/runtime/facts")
    public Mono<RuntimeFactReceipt> accept(@RequestBody RuntimeFact fact) {
        return Mono.fromCallable(() -> facts.accept(fact)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api/internal/runtime/facts/batch")
    public Mono<List<RuntimeFactReceipt>> acceptBatch(@RequestBody List<RuntimeFact> batch) {
        return Mono.fromCallable(() -> facts.acceptBatch(batch))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

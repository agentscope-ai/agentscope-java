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
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalRuntimeFacts;
import io.agentscope.builder.runtime.protocol.RuntimeFact;
import io.agentscope.builder.runtime.protocol.RuntimeFactReceipt;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class RuntimeFactsController {
    private final PortalRuntimeFacts facts;

    public RuntimeFactsController(PortalRuntimeFacts facts) {
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

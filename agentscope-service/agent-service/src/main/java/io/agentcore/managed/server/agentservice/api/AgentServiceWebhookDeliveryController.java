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

import io.agentcore.managed.server.agentservice.session.AgentServiceWebhooks;
import io.agentcore.managed.server.runtime.protocol.RuntimeWebhookDelivery;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/internal/runtime/webhooks")
public class AgentServiceWebhookDeliveryController {
    private final AgentServiceWebhooks webhooks;

    public AgentServiceWebhookDeliveryController(AgentServiceWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/claim")
    public Mono<RuntimeWebhookDelivery.Batch> claim(
            @RequestBody RuntimeWebhookDelivery.ClaimRequest request) {
        return Mono.fromCallable(() -> webhooks.claim(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/validate")
    public Mono<Map<String, Object>> validate(@RequestBody RuntimeWebhookDelivery.Proof proof) {
        return Mono.fromCallable(() -> webhooks.validate(proof))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/receipts")
    public Mono<Map<String, Object>> finish(@RequestBody RuntimeWebhookDelivery.Receipt receipt) {
        return Mono.fromCallable(() -> webhooks.finish(receipt))
                .subscribeOn(Schedulers.boundedElastic());
    }
}

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

import io.agentscope.builder.web.coord.BuilderInstanceId;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.EnvironmentWorkQueue;
import io.agentscope.builder.web.managed.ExternalSandboxRegistry;
import io.agentscope.builder.web.managed.service.HandsMetrics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Observability for Hands workers, turn coordination, and lease health. */
@RestController
@RequestMapping("/api/hands")
public class HandsObservabilityController {

    private final EnvironmentWorkQueue workQueue;
    private final ExternalSandboxRegistry registry;
    private final HandsMetrics handsMetrics;
    private final CoordinationStore coordinationStore;
    private final BuilderInstanceId instanceId;

    public HandsObservabilityController(
            EnvironmentWorkQueue workQueue,
            ExternalSandboxRegistry registry,
            HandsMetrics handsMetrics,
            CoordinationStore coordinationStore,
            BuilderInstanceId instanceId) {
        this.workQueue = workQueue;
        this.registry = registry;
        this.handsMetrics = handsMetrics;
        this.coordinationStore = coordinationStore;
        this.instanceId = instanceId;
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromSupplier(
                () -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("brainInstanceId", instanceId.get());
                    body.put("pendingWorkItems", workQueue.pendingCount());
                    body.put("localSandboxRegistrySize", registry.size());
                    body.put("workerHeartbeats", workQueue.workerHeartbeats());
                    body.put("sessionHandsMetrics", handsMetrics.snapshotAll());
                    return body;
                });
    }
}

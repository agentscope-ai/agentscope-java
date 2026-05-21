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
package io.agentscope.harness.claw.web.api;

import io.agentscope.harness.claw.web.runtime.ClawRuntimeClient;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Debug info endpoint for the admin console.
 *
 * <ul>
 *   <li>{@code GET /api/admin/debug/info} — returns log stream URL and claw status
 * </ul>
 *
 * <p>The actual live log stream is hosted at agentscope-claw's
 * {@code /api/admin/runtime/logs} (SSE). The admin UI connects to that URL directly using its
 * admin JWT.
 *
 * <p>Requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/debug")
public class DebugController {

    private final ClawRuntimeClient clawClient;

    public DebugController(ClawRuntimeClient clawClient) {
        this.clawClient = clawClient;
    }

    @GetMapping("/info")
    public Mono<Map<String, Object>> debugInfo() {
        return Mono.fromCallable(
                () ->
                        Map.of(
                                "logStreamUrl", clawClient.logStreamUrl(),
                                "clawUrl", clawClient.clawUrl(),
                                "clawReachable", clawClient.isClawReachable()));
    }
}

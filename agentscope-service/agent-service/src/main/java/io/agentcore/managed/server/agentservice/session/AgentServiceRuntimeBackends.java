/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.session;

import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/** Registry binds a Session to a backend kind; configured URLs address replicated services. */
@Service
public class AgentServiceRuntimeBackends {
    private static final Set<String> GO_KINDS =
            Set.of("external", "hosted-runtime", "team", "workflow", "mixed");
    private final AgentServiceSessionRepository sessions;
    private final WebClient managed;
    private final WebClient orchestration;

    public AgentServiceRuntimeBackends(
            AgentServiceSessionRepository sessions,
            @Value("${managed-agents.agent-harness-runtime-url:http://localhost:8082}")
                    String managedUrl,
            @Value(
                            "${managed-agents.orchestration-runtime-url:${managed-agents.control-plane-url:http://localhost:8081}}")
                    String orchestrationUrl) {
        this.sessions = sessions;
        this.managed = client(managedUrl);
        this.orchestration = client(orchestrationUrl);
    }

    public WebClient kind(String kind) {
        if ("managed".equals(kind)) return managed;
        if (kind != null && GO_KINDS.contains(kind)) return orchestration;
        throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported runtime kind");
    }

    public WebClient session(String id) {
        var row =
                sessions.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return kind(row.runtimeKind);
    }

    private static WebClient client(String url) {
        return WebClient.builder()
                .baseUrl(url)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}

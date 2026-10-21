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
package io.agentscope.builder.portal.session;

import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/** Registry binds a Session to a backend kind; configured URLs address replicated services. */
@Service
public class PortalRuntimeBackends {
    private static final Set<String> GO_KINDS =
            Set.of("external", "hosted-runtime", "team", "workflow", "mixed");
    private final PortalSessionRepository sessions;
    private final WebClient managed;
    private final WebClient orchestration;

    public PortalRuntimeBackends(
            PortalSessionRepository sessions,
            @Value("${builder.data-plane-url:http://localhost:8082}") String managedUrl,
            @Value(
                            "${builder.orchestration-runtime-url:${builder.control-plane-url:http://localhost:8081}}")
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

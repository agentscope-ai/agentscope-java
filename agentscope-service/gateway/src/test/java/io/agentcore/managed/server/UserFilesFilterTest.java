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
package io.agentcore.managed.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class UserFilesFilterTest {
    @Test
    void disabledDeploymentRejectsAllPublicFileEntryPoints() {
        var filter = new UserFilesFilter(false);
        for (String path :
                List.of(
                        "/api/agents/a/workspace/file",
                        "/api/workspaces/w/files",
                        "/api/v1/agent-sessions/s/files",
                        "/v1/sessions/s/files/f/content",
                        "/api/v1/agent-sessions/s/turns/t/artifacts",
                        "/api/v1/issues/i/attachments",
                        "/v1/sessions/s/%66iles")) {
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)));
            var forwarded = new AtomicBoolean();
            StepVerifier.create(
                            filter.filter(
                                    exchange,
                                    ignored -> {
                                        forwarded.set(true);
                                        return Mono.empty();
                                    }))
                    .verifyComplete();
            assertFalse(forwarded.get(), path);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
            StepVerifier.create(exchange.getResponse().getBodyAsString())
                    .assertNext(body -> assertTrue(body.contains("user_files_disabled")))
                    .verifyComplete();
        }
    }

    @Test
    void disabledFilesDoNotBlockTurnsCheckpointsOrWorkerProtocol() {
        for (String path :
                List.of(
                        "/api/auth/login",
                        "/v1/sessions/s/turns",
                        "/api/v1/agent-sessions/s/checkpoints",
                        "/v1/tool-workers/environments/e/tool-tasks/claim",
                        "/actuator/health",
                        "/workspace")) {
            var forwarded = new AtomicBoolean();
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            StepVerifier.create(
                            new UserFilesFilter(false)
                                    .filter(
                                            exchange,
                                            ignored -> {
                                                forwarded.set(true);
                                                return Mono.empty();
                                            }))
                    .verifyComplete();
            assertTrue(forwarded.get(), path);
        }
    }

    @Test
    void enabledDeploymentPreservesExistingFileRoutes() {
        var forwarded = new AtomicBoolean();
        StepVerifier.create(
                        new UserFilesFilter(true)
                                .filter(
                                        MockServerWebExchange.from(
                                                MockServerHttpRequest.get("/v1/sessions/s/files")),
                                        ignored -> {
                                            forwarded.set(true);
                                            return Mono.empty();
                                        }))
                .verifyComplete();
        assertTrue(forwarded.get());
    }
}

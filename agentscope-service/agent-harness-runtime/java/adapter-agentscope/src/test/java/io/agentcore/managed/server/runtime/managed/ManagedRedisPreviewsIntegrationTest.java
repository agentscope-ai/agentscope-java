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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.test.StepVerifier;

@EnabledIfEnvironmentVariable(named = "MANAGED_REDIS_TEST_URI", matches = ".+")
class ManagedRedisPreviewsIntegrationTest {
    @Test
    void brainPublishesOnlyToAuthorizedLiveServiceNodes() {
        var scope = new ManagedScope("integration", "preview", UUID.randomUUID().toString());
        var mapper = new ObjectMapper();
        try (var brainRedis =
                        new RedisManagedInfrastructure(System.getenv("MANAGED_REDIS_TEST_URI"), 1);
                var apiRedis =
                        new RedisManagedInfrastructure(System.getenv("MANAGED_REDIS_TEST_URI"), 1);
                var brain = new ManagedSessionPreviews(brainRedis, mapper);
                var api = new ManagedSessionPreviews(apiRedis, mapper);
                var connection = api.open(scope)) {
            var producer = brain.begin(scope, "turn", "call", 1, "REASONING");
            StepVerifier.create(connection.events())
                    .then(
                            () ->
                                    producer.accept(
                                            new ManagedSessionPreviews.TextDelta(
                                                    "reply", List.of("live"))))
                    .assertNext(event -> assertEquals("event_start", event.event()))
                    .assertNext(event -> assertEquals("event_delta", event.event()))
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));
            assertEquals(
                    1L,
                    apiRedis.busCommands().zcard("ma:preview:interest:" + scope.messageGroup()));
        }
    }
}

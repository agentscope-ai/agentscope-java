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
package io.agentcore.managed.server.web.catalog.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectiveAgentDefinitionTest {
    @Test
    void overlaysSessionFieldsWithoutMutatingFrozenDefinitionOrResourceBindings() {
        var snapshot =
                Map.<String, Object>of(
                        "model",
                        "base",
                        "system",
                        "original",
                        "maxIters",
                        20,
                        "skills",
                        List.of("review"),
                        "definitionDigest",
                        "frozen");
        var effective =
                EffectiveAgentDefinition.resolve(
                        snapshot,
                        "{\"model\":\"selected\",\"system\":\"session\",\"maxIters\":3,\"contextPolicy\":{}}");
        assertEquals("selected", effective.get("model"));
        assertEquals("session", effective.get("system"));
        assertEquals(3, effective.get("maxIters"));
        assertEquals(snapshot.get("skills"), effective.get("skills"));
        assertEquals("base", snapshot.get("model"));
        assertEquals("frozen", effective.get("definitionDigest"));
        assertEquals(snapshot, EffectiveAgentDefinition.resolve(snapshot, "{\"model\":null}"));
    }

    @Test
    void invalidOverridesCannotSilentlyFallBackOrAddExecutionGrants() {
        for (String value :
                List.of(
                        "[]",
                        "broken",
                        "{\"tools\":[]}",
                        "{\"vaultIds\":[\"other\"]}",
                        "{\"system\":{}}",
                        "{\"model\":\"\"}",
                        "{\"maxIters\":1.5}",
                        "{\"maxIters\":0}",
                        "{\"maxIters\":10001}",
                        "{\"contextPolicy\":false}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EffectiveAgentDefinition.resolve(Map.of("model", "base"), value));
            var error =
                    assertThrows(
                            ManagedStateException.class,
                            () ->
                                    ManagedSessionProtocol.validateAgent(
                                            Map.of(
                                                    "agentSnapshot", Map.of("model", "base"),
                                                    "session",
                                                            Map.of("agentOverridesJson", value))));
            assertEquals("invalid_request", error.code());
        }
    }
}

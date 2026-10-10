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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * {@code HarnessAgent.Builder.agentId(...)}: a trimmed id reaches both {@code getAgentId()} and
 * the legacy state dir (same namespace key), blank keeps the generated UUID with the namespace
 * falling back to name, and an invalid id is rejected before any state dir is created.
 */
@HarnessQuiescence
class HarnessAgentIdTest {

    @TempDir Path stateHome;
    @TempDir Path workspace;

    private String previousStateHome;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (previousStateHome != null) {
            System.setProperty("agentscope.state.home", previousStateHome);
        } else {
            System.clearProperty("agentscope.state.home");
        }
    }

    @Test
    void agentId_forwardedTrimmed_blankFallsBack_invalidRejected() throws Exception {
        Files.createDirectories(workspace);

        HarnessAgent custom =
                HarnessAgent.builder()
                        .name("t")
                        .agentId("  harness-x  ")
                        .legacySessionHistory(true)
                        .model(stubModel())
                        .workspace(workspace)
                        .build();
        try {
            assertEquals("harness-x", custom.getAgentId(), "trimmed id should reach getAgentId()");
            assertTrue(
                    Files.isDirectory(stateHome.resolve("harness-x")),
                    "state dir should use the same trimmed id");
        } finally {
            custom.close();
        }

        HarnessAgent blank =
                HarnessAgent.builder()
                        .name("fallback-name")
                        .agentId("   ")
                        .legacySessionHistory(true)
                        .model(stubModel())
                        .workspace(workspace)
                        .build();
        try {
            assertDoesNotThrow(
                    () -> UUID.fromString(blank.getAgentId()), "blank id keeps a generated UUID");
            assertTrue(
                    Files.isDirectory(stateHome.resolve("fallback-name")),
                    "namespace key falls back to name");
        } finally {
            blank.close();
        }

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarnessAgent.builder()
                                .name("t")
                                .agentId("../evil")
                                .legacySessionHistory(true)
                                .model(stubModel())
                                .workspace(workspace)
                                .build(),
                "traversal id is rejected");
    }

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("done").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}

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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentModelStringTest {

    @TempDir Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        ModelRegistry.reset();
        Files.createDirectories(workspace);
    }

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void builder_modelString_resolvesViaRegistry() {
        Model registered = stubModel("ok");
        ModelRegistry.register("reg-main", registered);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model("reg-main")
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        assertSame(registered, agent.getDelegate().getModel());
    }

    @Test
    void builder_modelString_unknownId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarnessAgent.builder()
                                .name("t")
                                .model("no-such-registry-model")
                                .workspace(workspace)
                                .abstractFilesystem(new LocalFilesystem(workspace))
                                .build());
    }

    @Test
    void subagentSpec_model_resolvedByDefaultResolver() {
        Model main = stubModel("main-reply");
        Model sub = stubModel("sub-reply");
        ModelRegistry.register("reg-sub", sub);

        SubagentSpec spec = new SubagentSpec("sa", "subagent");
        spec.setSysPrompt("You are a test subagent.");
        spec.setModel("reg-sub");

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .name("main")
                        .model(main)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .subagent(spec)
                        .buildSubagentEntries(workspace);

        SubagentEntry entry =
                entries.stream().filter(e -> "sa".equals(e.name())).findFirst().orElseThrow();

        HarnessAgent subAgent = (HarnessAgent) entry.factory().create();
        assertSame(sub, subAgent.getDelegate().getModel());
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}

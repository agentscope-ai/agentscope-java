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
package io.agentcore.managed.server.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessAgentBuildServiceHttpClientTest {
    @TempDir Path root;

    @Test
    void manyCachedAgentsDoNotAllocateTwoWebHttpSelectorsPerSession() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        var agents = new ArrayList<HarnessAgent>();
        long before = selectors();
        try {
            for (int index = 0; index < 16; index++) {
                agents.add(
                        HarnessAgentBuildService.newAgentBuilder()
                                .name("agent-" + index)
                                .model(model)
                                .workspace(root.resolve("session-" + index))
                                .disableSubagents()
                                .disableDefaultWorkspaceSkills()
                                .build());
            }
            assertThat(agents)
                    .allSatisfy(
                            agent ->
                                    assertThat(agent.getToolkit().getToolNames())
                                            .contains("web_fetch", "web_search"));
            // Live cached Agents retain their tools. Reclamation must not depend on GC or closing
            // them.
            assertThat(selectors() - before).isLessThanOrEqualTo(1);
        } finally {
            agents.forEach(HarnessAgent::close);
        }
    }

    private static long selectors() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(
                        thread ->
                                thread.getName().startsWith("HttpClient-")
                                        && thread.getName().endsWith("-SelectorManager"))
                .count();
    }
}

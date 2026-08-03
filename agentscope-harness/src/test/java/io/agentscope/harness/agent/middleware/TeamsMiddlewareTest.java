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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.tool.TeamTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamsMiddlewareTest {

    @Test
    void renderIncludesLeadGuidance() {
        TeamContext ctx =
                new TeamContext(
                        "research",
                        "default",
                        "ship docs",
                        "lead",
                        true,
                        List.of(new TeamContext.MemberSnapshot("lead", "a", "working")),
                        List.of("createTask", "assignTask"));
        String section = TeamsMiddleware.renderTeamSection(ctx);
        assertTrue(section.contains("Agent Team"));
        assertTrue(section.contains("lead"));
        assertTrue(section.contains("ship docs"));
        assertTrue(section.contains("createTask"));
    }

    @Test
    void teamToolRejectsDisallowedAction() {
        TeamContext ctx =
                new TeamContext(
                        "research",
                        "default",
                        "ship",
                        "worker",
                        false,
                        List.of(),
                        List.of("listTasks", "claimTask"));
        TeamClient client =
                new io.agentscope.harness.agent.team.LocalTeamClient(
                        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        TeamTool tool = new TeamTool(client, ctx);
        String out =
                tool.team(
                        "createTask",
                        null,
                        "x",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertTrue(out.contains("error"));
        assertFalse(out.contains("\"taskId\""));
    }
}

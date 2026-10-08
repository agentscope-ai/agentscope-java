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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Per-agent daily ledgers ({@code memory/YYYY-MM-DD.<agentId>.md}), written when agents share
 * long-term memory (#3436).
 */
class MemoryMaintenanceMiddlewareDailyLedgerTest {

    @TempDir Path workspace;

    @Test
    void dailyLedgerPathCarriesSanitizedOwner() {
        try (WorkspaceManager ws = new WorkspaceManager(workspace)) {
            assertEquals("memory/2026-10-08.md", ws.dailyLedgerPath("2026-10-08"));

            ws.setDailyLedgerOwner("team/planner v2");
            assertEquals("memory/2026-10-08.team_planner_v2.md", ws.dailyLedgerPath("2026-10-08"));

            ws.setDailyLedgerOwner(" ");
            assertEquals("memory/2026-10-08.md", ws.dailyLedgerPath("2026-10-08"));
        }
    }

    @Test
    void archivesExpiredPerAgentLedgers() throws Exception {
        Path memory = workspace.resolve("memory");
        Files.createDirectories(memory);
        String fresh = LocalDate.now().plusDays(1) + ".planner.md";
        Files.writeString(memory.resolve("2020-01-01.md"), "- old");
        Files.writeString(memory.resolve("2020-01-01.planner.md"), "- old planner");
        Files.writeString(memory.resolve(fresh), "- fresh");

        try (WorkspaceManager ws =
                new WorkspaceManager(workspace, new LocalFilesystem(workspace, true, 10), null)) {
            MemoryMaintenanceMiddleware middleware =
                    new MemoryMaintenanceMiddleware(
                            ws,
                            null,
                            30,
                            180,
                            Duration.ofMinutes(30),
                            IsolationScope.USER,
                            (name, minGap) -> true);
            Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
            middleware
                    .onAgent(
                            null,
                            RuntimeContext.empty(),
                            new AgentInput(List.of(userMsg)),
                            in -> Flux.<AgentEvent>just(new AgentEndEvent("reply-1")))
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "maintenance task must quiesce");
        }

        assertTrue(Files.exists(memory.resolve("archive/2020-01-01.md")));
        assertTrue(
                Files.exists(memory.resolve("archive/2020-01-01.planner.md")),
                "an expired per-agent ledger must be archived like a plain one");
        assertTrue(Files.exists(memory.resolve(fresh)), "a fresh ledger must stay in place");
    }
}

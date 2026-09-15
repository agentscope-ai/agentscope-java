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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemorySaveToolTest {

    @TempDir Path workspace;

    // Regression for #3088: memory_save appends to the same memory/YYYY-MM-DD.md ledger as
    // MemoryFlushManager, so it must use the same zone-aware convention for the file name and the
    // section header.
    @Test
    void memorySave_writesZoneAwareHeaderAndMatchingFileName() throws Exception {
        Instant instant = Instant.parse("2026-09-10T20:00:00Z");
        Clock clock = Clock.fixed(instant, ZoneId.of("Asia/Shanghai"));
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            new MemorySaveTool(workspaceManager, clock).memorySave(rc, "- user prefers dark mode");
        }

        Path daily = workspace.resolve("memory/2026-09-11.md");
        assertTrue(Files.exists(daily), "daily ledger should follow the clock's local date");
        String content = Files.readString(daily);
        assertTrue(
                content.contains("## Memory Save — 2026-09-11T04:00:00+08:00"),
                "header should carry the clock's offset: " + content);
        assertTrue(
                Files.readString(workspace.resolve("MEMORY.md")).contains("dark mode"),
                "memory_save should still append the fact to MEMORY.md");
    }
}

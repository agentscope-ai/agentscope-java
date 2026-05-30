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
package io.agentscope.harness.agent.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies memory maintenance uses LocalFilesystem glob results for root daily ledgers. */
class MemoryMaintenanceHookFilesystemTest {

    @Test
    void postCall_archivesRootDailyLedgerWhenUsingLocalFilesystem(@TempDir Path tmp)
            throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        WorkspaceManager workspaceManager = new WorkspaceManager(tmp, fs);

        Path memoryDir = Files.createDirectories(tmp.resolve("memory"));
        String fileName = LocalDate.now().minusDays(10) + ".md";
        Path dailyLedger = memoryDir.resolve(fileName);
        Files.writeString(dailyLedger, "old entry");

        MemoryMaintenanceHook hook =
                new MemoryMaintenanceHook(workspaceManager, null, 1, 365, Duration.ZERO);

        Msg finalMessage =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("done").build())
                        .build();

        hook.onEvent(new PostCallEvent(mock(Agent.class), finalMessage)).block();

        Path archived = memoryDir.resolve("archive").resolve(fileName);
        assertFalse(Files.exists(dailyLedger));
        assertTrue(Files.isRegularFile(archived));
        assertEquals("old entry", Files.readString(archived));
    }
}

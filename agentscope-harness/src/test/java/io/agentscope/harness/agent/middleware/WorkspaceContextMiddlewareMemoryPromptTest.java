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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ensures Memory Recall / Persistence guidance and {@code <memory_context>} stay aligned with
 * {@code disableMemoryTools} / {@code disableMemoryHooks}.
 */
class WorkspaceContextMiddlewareMemoryPromptTest {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private WorkspaceManager track(WorkspaceManager wm) {
        openManagers.add(wm);
        return wm;
    }

    @TempDir Path workspace;

    @Test
    void onSystemPromptBuildsWorkspaceContextOnBoundedElastic() {
        Thread callerThread = Thread.currentThread();
        AtomicReference<Thread> readThread = new AtomicReference<>();
        WorkspaceManager wm =
                track(
                        new WorkspaceManager(workspace) {
                            @Override
                            public String readAgentsMd(RuntimeContext rc) {
                                readThread.set(Thread.currentThread());
                                return "agent persona";
                            }
                        });
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();

        assertNotNull(prompt);
        assertTrue(prompt.contains("agent persona"));
        assertNotNull(readThread.get());
        assertNotSame(
                callerThread, readThread.get(), "workspace context read ran on caller thread");
    }

    @Test
    void onSystemPromptHandlesNullAndNonNewlineBasePrompts() {
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String promptWithoutBase = mw.onSystemPrompt(null, null, null).block();
        String promptWithBase = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE").block();

        assertNotNull(promptWithoutBase);
        assertFalse(promptWithoutBase.startsWith("null"));
        assertTrue(promptWithoutBase.contains("## Domain Knowledge"));
        assertNotNull(promptWithBase);
        assertTrue(promptWithBase.startsWith("BASE\n"));
    }

    @Test
    void defaultFlags_includeMemoryRecallPersistenceAndContext() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "remember: cats prefer windowsills");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("## Domain Knowledge"));
        assertTrue(prompt.contains("## Memory Recall"));
        assertTrue(prompt.contains("memory_search"));
        assertTrue(prompt.contains("## Memory Persistence"));
        assertTrue(prompt.contains("memory_save"));
        assertTrue(prompt.contains("automatically extracted"));
        assertTrue(prompt.contains("<memory_context>"));
        assertTrue(prompt.contains("cats prefer windowsills"));
    }

    @Test
    void disableMemoryTools_omitsToolGuidance_keepsMemoryContextAndAutoExtract() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "prefer dark mode");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, true, false);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("## Domain Knowledge"));
        assertFalse(prompt.contains("## Memory Recall"));
        assertFalse(prompt.contains("memory_search"));
        assertFalse(prompt.contains("memory_get"));
        assertFalse(prompt.contains("memory_save"));
        assertTrue(prompt.contains("## Memory Persistence"));
        assertTrue(prompt.contains("automatically extracted"));
        assertTrue(prompt.contains("<memory_context>"));
        assertTrue(prompt.contains("prefer dark mode"));
    }

    @Test
    void disableMemoryHooks_keepsTools_omitsAutoExtract() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "prefer dark mode");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, false, true);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("## Memory Recall"));
        assertTrue(prompt.contains("memory_save"));
        assertFalse(prompt.contains("automatically extracted"));
        assertTrue(prompt.contains("<memory_context>"));
    }

    @Test
    void bothDisabled_omitsMemoryGuidanceAndMemoryContext() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "should not appear in prompt");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, true, true);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("## Domain Knowledge"));
        assertFalse(prompt.contains("## Memory Recall"));
        assertFalse(prompt.contains("## Memory Persistence"));
        assertFalse(prompt.contains("memory_search"));
        assertFalse(prompt.contains("memory_save"));
        assertFalse(prompt.contains("automatically extracted"));
        assertFalse(prompt.contains("<memory_context>"));
        assertFalse(prompt.contains("should not appear in prompt"));
        assertTrue(prompt.contains("<agents_context>"));
        assertTrue(prompt.contains("<domain_knowledge_context>"));
    }
}

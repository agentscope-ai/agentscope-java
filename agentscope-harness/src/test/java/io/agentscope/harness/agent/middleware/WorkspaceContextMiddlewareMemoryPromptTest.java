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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
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
 * Ensures Memory Recall / Persistence guidance and memory reference material stay aligned with
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

        RuntimeContext rc = RuntimeContext.empty();
        String prompt = WorkspacePromptTestSupport.render(mw, rc, "BASE\n");

        assertNotNull(prompt);
        assertTrue(prompt.contains("agent persona"));
        assertTrue(prompt.contains("## Runtime Environment"));
        assertFalse(prompt.contains("AgentStateStore Context"));
        assertNotNull(readThread.get());
        assertNotSame(
                callerThread, readThread.get(), "workspace context read ran on caller thread");
    }

    @Test
    void onSystemPromptHandlesNullAndNonNewlineBasePrompts() {
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String promptWithoutBase = WorkspacePromptTestSupport.render(mw, null, null);
        String promptWithBase =
                WorkspacePromptTestSupport.render(mw, RuntimeContext.empty(), "BASE");

        assertNotNull(promptWithoutBase);
        assertFalse(promptWithoutBase.startsWith("null"));
        assertFalse(promptWithoutBase.contains("## Domain Knowledge"));
        assertNotNull(promptWithBase);
        assertTrue(promptWithBase.startsWith("BASE\n"));
    }

    @Test
    void defaultFlags_includeMemoryRecallPersistenceAndContext() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "remember: cats prefer windowsills");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        RuntimeContext rc = RuntimeContext.empty();
        String prompt = WorkspacePromptTestSupport.render(mw, rc, "BASE\n");
        assertNotNull(prompt);
        assertFalse(prompt.contains("## Domain Knowledge"));
        assertTrue(prompt.contains("## Memory Recall"));
        assertTrue(prompt.contains("memory_search"));
        assertTrue(prompt.contains("## Memory Persistence"));
        assertTrue(prompt.contains("memory_save"));
        assertTrue(prompt.contains("automatically extracted"));
        assertFalse(prompt.contains("<memory_context>"));
        assertFalse(prompt.contains("cats prefer windowsills"));
        assertTrue(
                rc.get(WorkspaceContextMaterials.class).items().stream()
                        .anyMatch(item -> item.content().contains("cats prefer windowsills")));
    }

    @Test
    void memoryOverBudgetKeepsNewestEntriesAndDropsTheMiddle() throws Exception {
        Files.writeString(
                workspace.resolve("MEMORY.md"),
                "# Long-term memory\n\n"
                        + "header ".repeat(60)
                        + "\nmiddle-fact-must-be-dropped\n"
                        + "older-entry ".repeat(700)
                        + "\nNEWEST: deploy freeze until Friday\n");
        var wm = track(new WorkspaceManager(workspace));
        var rc = RuntimeContext.empty();
        int maxTokens = 300;
        new WorkspaceContextMiddleware(wm, maxTokens).onSystemPrompt(null, rc, "BASE").block();

        String memory =
                rc.get(WorkspaceContextMaterials.class).items().stream()
                        .filter(item -> item.kind().equals("memory"))
                        .findFirst()
                        .orElseThrow()
                        .content();

        assertTrue(
                memory.contains("NEWEST: deploy freeze until Friday"),
                "the most recently appended entry must survive the budget cut");
        assertFalse(
                memory.contains("middle-fact-must-be-dropped"),
                "the dropped middle must not reappear");
        assertTrue(memory.contains("memory truncated"));
        assertTrue(memory.startsWith("# Long-term memory"), "head structure is preserved");
        assertTrue(
                memory.length() <= maxTokens * 4,
                "the block stays within the char budget it was given");
    }

    @Test
    void memoryWithinBudgetIsKeptVerbatim() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "# Memory\n\nprefers tabs\n");
        var wm = track(new WorkspaceManager(workspace));
        var rc = RuntimeContext.empty();
        new WorkspaceContextMiddleware(wm, 300).onSystemPrompt(null, rc, "BASE").block();

        String memory =
                rc.get(WorkspaceContextMaterials.class).items().stream()
                        .filter(item -> item.kind().equals("memory"))
                        .findFirst()
                        .orElseThrow()
                        .content();

        assertEquals("# Memory\n\nprefers tabs", memory);
        assertFalse(memory.contains("memory truncated"));
    }

    @Test
    void budgetBelowNoticeLengthStillKeepsTheNewestTail() throws Exception {
        var wm = track(new WorkspaceManager(workspace));
        var mw = new WorkspaceContextMiddleware(wm, 8000);
        var truncate =
                WorkspaceContextMiddleware.class.getDeclaredMethod(
                        "truncateToTokenBudget", String.class, int.class);
        truncate.setAccessible(true);

        String text = "old-entry ".repeat(200) + "NEWEST";
        String out = (String) truncate.invoke(mw, text, 4);

        assertEquals(16, out.length());
        assertTrue(out.endsWith("NEWEST"));
    }

    @Test
    void exhaustedWorkspaceBudgetDoesNotKeepMemory() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "required ".repeat(100));
        Files.writeString(workspace.resolve("MEMORY.md"), "must not fit");
        var wm = track(new WorkspaceManager(workspace));
        var rc = RuntimeContext.empty();
        new WorkspaceContextMiddleware(wm, 1).onSystemPrompt(null, rc, "BASE").block();
        var materials = rc.get(WorkspaceContextMaterials.class);
        assertTrue(
                materials.items().stream()
                        .filter(item -> item.kind().equals("memory"))
                        .allMatch(item -> item.content().isEmpty()));
    }

    @Test
    void disableMemoryTools_omitsToolGuidance_keepsMemoryContextAndAutoExtract() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "prefer dark mode");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, true, false);

        RuntimeContext rc = RuntimeContext.empty();
        String prompt = WorkspacePromptTestSupport.render(mw, rc, "BASE\n");
        assertNotNull(prompt);
        assertFalse(prompt.contains("## Domain Knowledge"));
        assertFalse(prompt.contains("## Memory Recall"));
        assertFalse(prompt.contains("memory_search"));
        assertFalse(prompt.contains("memory_get"));
        assertFalse(prompt.contains("memory_save"));
        assertTrue(prompt.contains("## Memory Persistence"));
        assertTrue(prompt.contains("automatically extracted"));
        assertFalse(prompt.contains("<memory_context>"));
        assertFalse(prompt.contains("prefer dark mode"));
        assertTrue(
                rc.get(WorkspaceContextMaterials.class).items().stream()
                        .anyMatch(item -> item.content().contains("prefer dark mode")));
    }

    @Test
    void disableMemoryHooks_keepsTools_omitsAutoExtract() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "prefer dark mode");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, false, true);

        RuntimeContext rc = RuntimeContext.empty();
        String prompt = WorkspacePromptTestSupport.render(mw, rc, "BASE\n");
        assertNotNull(prompt);
        assertTrue(prompt.contains("## Memory Recall"));
        assertTrue(prompt.contains("memory_save"));
        assertFalse(prompt.contains("automatically extracted"));
        assertFalse(prompt.contains("<memory_context>"));
    }

    @Test
    void bothDisabled_omitsMemoryGuidanceAndMemoryContext() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "should not appear in prompt");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, true, true);

        RuntimeContext rc = RuntimeContext.empty();
        String prompt = WorkspacePromptTestSupport.render(mw, rc, "BASE\n");
        assertNotNull(prompt);
        assertFalse(prompt.contains("## Domain Knowledge"));
        assertFalse(prompt.contains("## Memory Recall"));
        assertFalse(prompt.contains("## Memory Persistence"));
        assertFalse(prompt.contains("memory_search"));
        assertFalse(prompt.contains("memory_save"));
        assertFalse(prompt.contains("automatically extracted"));
        assertFalse(prompt.contains("<memory_context>"));
        assertFalse(prompt.contains("should not appear in prompt"));
        assertFalse(prompt.contains("<working_principles "));
        assertFalse(prompt.contains("<domain_knowledge_context>"));
    }
}

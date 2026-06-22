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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Dedicated tool for persisting user memories to both {@code MEMORY.md} and
 * today's daily ledger ({@code memory/YYYY-MM-DD.md}).
 *
 * <p>This is the <b>only</b> sanctioned entry point for the agent to write long-term
 * memories. The system prompt directs the LLM to use this tool instead of
 * {@code write_file}/{@code edit_file} on memory paths, eliminating the
 * non-determinism of the LLM choosing arbitrary file names or skipping the
 * daily ledger.
 *
 * <p>Writes are append-only; deduplication and size-trimming are handled by the
 * periodic {@link io.agentscope.harness.agent.memory.MemoryConsolidator}.
 */
public class MemorySaveTool {

    private final WorkspaceManager workspaceManager;

    public MemorySaveTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Tool(
            name = "memory_save",
            description =
                    "Persist one or more facts to long-term memory. Use whenever the user asks you"
                        + " to remember something, or when you observe important preferences,"
                        + " decisions, or context worth keeping across conversations. Do NOT use"
                        + " write_file or edit_file on MEMORY.md — always use this tool instead.")
    public String memorySave(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "content",
                            description =
                                    "Markdown bullet list of facts to remember. Each bullet should"
                                            + " be a concise, self-contained fact. Example:\\n"
                                            + "- User prefers dark mode\\n"
                                            + "- Project deadline is 2026-07-01")
                    String content) {
        if (content == null || content.isBlank()) {
            return "Error: content is required";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();

        String section = "\n" + content.strip() + "\n";

        workspaceManager.appendUtf8WorkspaceRelative(rc, WorkspaceConstants.MEMORY_MD, section);

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String dailyEntry =
                String.format(
                        "\n## Memory Save — %s\n%s\n", Instant.now().toString(), content.strip());
        workspaceManager.appendUtf8WorkspaceRelative(rc, dailyPath, dailyEntry);

        long count = content.strip().lines().filter(l -> l.stripLeading().startsWith("-")).count();
        if (count == 0) {
            count = 1;
        }
        return "Saved " + count + " memor" + (count == 1 ? "y" : "ies") + " to MEMORY.md";
    }
}

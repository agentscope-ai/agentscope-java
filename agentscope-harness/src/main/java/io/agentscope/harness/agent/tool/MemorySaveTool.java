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
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultBlock;
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
                    "Persist durable facts to long-term memory. Use whenever the user asks you to"
                        + " remember something, or when you observe a preference, decision,"
                        + " constraint, or ongoing-work fact worth keeping across conversations. Do"
                        + " NOT use write_file or edit_file on MEMORY.md — always use this tool"
                        + " instead. Never save secrets, credentials, or transient status.")
    public ToolResultBlock memorySave(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "content",
                            description =
                                    "Markdown bullet list of durable facts. Each bullet must be"
                                            + " self-contained and carry its provenance: [user]"
                                            + " when the user stated it, [observed] when it came"
                                            + " from a tool result or file, [inferred] when you"
                                            + " inferred it. Add the source and an ISO date when"
                                            + " the fact can change (owner, status, deadline)."
                                            + " If a new fact conflicts with existing memory, keep"
                                            + " both and mark the newer one with its source and"
                                            + " date instead of silently replacing it. Example:\\n"
                                            + "- [user] User prefers dark mode (2026-07-01)\\n"
                                            + "- [observed] Project deadline is 2026-07-01"
                                            + " (docs/plan.md)")
                    String content) {
        if (content == null || content.isBlank()) {
            return ToolResultBlock.error("content is required")
                    .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT));
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();

        String section = "\n" + content.strip() + "\n";

        workspaceManager.appendMemoryFileUtf8(rc, WorkspaceConstants.MEMORY_MD, section);

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String dailyEntry =
                String.format(
                        "\n## Memory Save — %s\n%s\n", Instant.now().toString(), content.strip());
        workspaceManager.appendMemoryFileUtf8(rc, dailyPath, dailyEntry);

        long count = content.strip().lines().filter(l -> l.stripLeading().startsWith("-")).count();
        if (count == 0) {
            count = 1;
        }
        return ToolResultBlock.success(
                "Saved " + count + " memor" + (count == 1 ? "y" : "ies") + " to MEMORY.md");
    }
}

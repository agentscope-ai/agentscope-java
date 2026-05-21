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
package io.agentscope.harness.coding.tools.finding;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.coding.reviewer.Finding;
import io.agentscope.harness.coding.reviewer.ReviewerFindingsService;
import java.util.UUID;

/**
 * Adds a new finding to the reviewer findings store. Mirrors open-swe's {@code add_finding} tool.
 */
public class AddFindingTool {

    private final ReviewerFindingsService findingsService;

    public AddFindingTool(ReviewerFindingsService findingsService) {
        this.findingsService = findingsService;
    }

    @Tool(
            description =
                    "Record a new code review finding. Call this for each real issue found in the"
                        + " PR diff. Anchor every finding to a line that the PR actually changes.")
    public String add_finding(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "severity",
                            description = "Severity: informational, low, medium, high, or critical")
                    String severity,
            @ToolParam(
                            name = "category",
                            description = "Category: correctness, security, perf, style, or flag")
                    String category,
            @ToolParam(name = "file", description = "File path containing the issue") String file,
            @ToolParam(name = "start_line", description = "Line number in the file") int startLine,
            @ToolParam(
                            name = "description",
                            description = "Description of the issue (1-4 sentences)")
                    String description,
            @ToolParam(
                            name = "suggestion",
                            description = "Optional code suggestion (only for tiny fixes ≤4 lines)")
                    String suggestion) {
        String threadId = resolveThreadId(runtimeContext);

        Finding finding = new Finding();
        finding.setId(UUID.randomUUID().toString());
        finding.setSeverity(severity);
        finding.setCategory(category);
        finding.setFile(file);
        finding.setStartLine(startLine);
        finding.setDescription(description);
        finding.setSuggestion(suggestion);
        finding.setStatus("open");

        findingsService.addFinding(threadId, finding);
        return "Finding added: id="
                + finding.getId()
                + " severity="
                + severity
                + " file="
                + file
                + ":"
                + startLine;
    }

    private static String resolveThreadId(RuntimeContext ctx) {
        if (ctx != null && ctx.getSessionKey() != null) {
            return ctx.getSessionKey().toIdentifier();
        }
        return "default";
    }
}

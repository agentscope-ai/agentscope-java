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

/**
 * Updates an existing reviewer finding.
 */
public class UpdateFindingTool {

    private final ReviewerFindingsService findingsService;

    public UpdateFindingTool(ReviewerFindingsService findingsService) {
        this.findingsService = findingsService;
    }

    @Tool(
            description =
                    "Update an existing reviewer finding. Use during re-reviews to mark resolved"
                            + " issues or revise severity/description.")
    public String update_finding(
            RuntimeContext runtimeContext,
            @ToolParam(name = "id", description = "Finding ID to update") String id,
            @ToolParam(name = "status", description = "New status: open or resolved (optional)")
                    String status,
            @ToolParam(name = "severity", description = "Updated severity (optional)")
                    String severity,
            @ToolParam(name = "description", description = "Updated description (optional)")
                    String description,
            @ToolParam(name = "suggestion", description = "Updated suggestion (optional)")
                    String suggestion,
            @ToolParam(name = "note", description = "Note explaining the change (optional)")
                    String note) {
        String threadId = resolveThreadId(runtimeContext);
        Finding finding = findingsService.getFinding(threadId, id);
        if (finding == null) {
            return "Error: finding not found: " + id;
        }
        if (status != null && !status.isBlank()) finding.setStatus(status);
        if (severity != null && !severity.isBlank()) finding.setSeverity(severity);
        if (description != null && !description.isBlank()) finding.setDescription(description);
        if (suggestion != null) finding.setSuggestion(suggestion.isBlank() ? null : suggestion);
        if (note != null && !note.isBlank()) finding.setNote(note);
        findingsService.updateFinding(threadId, finding);
        return "Finding updated: id=" + id + " status=" + finding.getStatus();
    }

    private static String resolveThreadId(RuntimeContext ctx) {
        if (ctx != null && ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return "default";
    }
}

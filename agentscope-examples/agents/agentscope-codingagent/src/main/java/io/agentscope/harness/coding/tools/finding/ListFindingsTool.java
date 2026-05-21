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
import io.agentscope.harness.coding.reviewer.Finding;
import io.agentscope.harness.coding.reviewer.ReviewerFindingsService;
import java.util.List;

/**
 * Lists all current reviewer findings. Mirrors open-swe's {@code list_findings} tool.
 */
public class ListFindingsTool {

    private final ReviewerFindingsService findingsService;

    public ListFindingsTool(ReviewerFindingsService findingsService) {
        this.findingsService = findingsService;
    }

    @Tool(description = "List all current reviewer findings for this PR review session.")
    public String list_findings(RuntimeContext runtimeContext) {
        String threadId = resolveThreadId(runtimeContext);
        List<Finding> findings = findingsService.listFindings(threadId);
        if (findings.isEmpty()) {
            return "No findings recorded yet.";
        }
        StringBuilder sb = new StringBuilder("Findings (" + findings.size() + " total):\n\n");
        for (Finding f : findings) {
            sb.append("- **[").append(f.getSeverity()).append("]** ");
            sb.append(f.getFile()).append(":").append(f.getStartLine()).append(" ");
            sb.append("(").append(f.getStatus()).append(") id=").append(f.getId()).append("\n");
            sb.append("  ").append(f.getDescription()).append("\n");
            if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
                sb.append("  Suggestion: ").append(f.getSuggestion()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    private static String resolveThreadId(RuntimeContext ctx) {
        if (ctx != null && ctx.getSessionKey() != null) {
            return ctx.getSessionKey().toIdentifier();
        }
        return "default";
    }
}

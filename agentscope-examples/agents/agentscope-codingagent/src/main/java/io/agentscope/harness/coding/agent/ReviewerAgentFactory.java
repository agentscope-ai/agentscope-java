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
package io.agentscope.harness.coding.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.coding.prompt.ReviewerSystemPrompt;
import java.nio.file.Path;

/**
 * Factory for the reviewer agent.
 *
 * <p>Mirrors {@code get_reviewer_agent()} from open-swe-main's {@code agent/reviewer.py}.
 * Configures a read-only {@link HarnessAgent} that:
 *
 * <ul>
 *   <li>Uses the reviewer system prompt with PR context
 *   <li>Only receives reviewer-specific tools (add_finding, update_finding, list_findings,
 *       publish_review, github_api_request, fetch_url, execute — no write/commit tools)
 *   <li>Runs in its own workspace, separate from the coding agent
 * </ul>
 */
public final class ReviewerAgentFactory {

    private ReviewerAgentFactory() {}

    /** Builds a reviewer agent for the given workspace with default settings. */
    public static HarnessAgent create(Path workspace, Toolkit reviewerToolkit) {
        return create(workspace, reviewerToolkit, null, null, 0);
    }

    /**
     * Builds a reviewer agent with PR context baked into the system prompt.
     *
     * @param workspace reviewer agent workspace directory
     * @param reviewerToolkit reviewer-specific tools only
     * @param repoOwner GitHub repo owner
     * @param repoName GitHub repo name
     * @param prNumber PR number
     */
    public static HarnessAgent create(
            Path workspace,
            Toolkit reviewerToolkit,
            String repoOwner,
            String repoName,
            int prNumber) {
        Model model = CodingAgentFactory.buildModel();
        String workingDir = workspace.toAbsolutePath().toString();
        String sysPrompt = ReviewerSystemPrompt.build(workingDir, repoOwner, repoName, prNumber);

        return HarnessAgent.builder()
                .name("open-swe-reviewer")
                .model(model)
                .sysPrompt(sysPrompt)
                .workspace(workspace)
                .toolkit(reviewerToolkit != null ? reviewerToolkit : new Toolkit())
                .maxIters(resolveMaxIters())
                .disableSubagents()
                .build();
    }

    private static int resolveMaxIters() {
        String env = System.getenv("REVIEWER_MAX_ITERS");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 30;
    }
}

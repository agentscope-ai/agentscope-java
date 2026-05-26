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
import io.agentscope.harness.coding.reviewer.GitHubReviewPublisher;
import io.agentscope.harness.coding.reviewer.ReviewerFindingsService;
import io.agentscope.harness.coding.tools.GitHubApiTool;

/**
 * Publishes the accumulated reviewer findings as a single GitHub PR review. Must be called exactly
 * once at the end of a reviewer run.
 */
public class PublishReviewTool {

    private final ReviewerFindingsService findingsService;
    private final GitHubReviewPublisher publisher;

    public PublishReviewTool(
            ReviewerFindingsService findingsService, GitHubReviewPublisher publisher) {
        this.findingsService = findingsService;
        this.publisher = publisher;
    }

    @Tool(
            description =
                    "Publish all recorded findings as a single GitHub PR review with inline"
                            + " comments. Call exactly ONCE at the end of every reviewer run,"
                            + " even when there are no findings (posts a 'no issues found'"
                            + " comment). Never call GitHub review APIs directly — use this tool.")
    public String publish_review(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "pr_url",
                            description =
                                    "GitHub PR URL (e.g."
                                            + " https://github.com/owner/repo/pull/123)")
                    String prUrl) {
        String threadId = resolveThreadId(runtimeContext);
        String token = GitHubApiTool.resolveToken(runtimeContext);
        try {
            publisher.publish(threadId, prUrl, token, findingsService);
            return "Review published successfully for " + prUrl;
        } catch (Exception e) {
            return "Error publishing review: " + e.getMessage();
        }
    }

    private static String resolveThreadId(RuntimeContext ctx) {
        if (ctx != null && ctx.getSessionKey() != null) {
            return ctx.getSessionKey().toIdentifier();
        }
        return "default";
    }
}

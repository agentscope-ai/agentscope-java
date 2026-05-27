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
package io.agentscope.harness.coding.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.function.Consumer;

/**
 * Tool for triggering the reviewer agent on a GitHub PR. When called by the coding agent, this
 * tool enqueues a reviewer run via the {@link Consumer} callback injected at construction time.
 */
public class RequestPrReviewTool {

    private final Consumer<String> reviewerDispatcher;

    /**
     * @param reviewerDispatcher callback that accepts a PR URL and asynchronously dispatches the
     *     reviewer agent
     */
    public RequestPrReviewTool(Consumer<String> reviewerDispatcher) {
        this.reviewerDispatcher = reviewerDispatcher;
    }

    @Tool(
            description =
                    "Request a code review from the reviewer agent for a GitHub pull request. Call"
                            + " this after pushing your changes and opening a PR. The reviewer"
                            + " will analyze the diff and post inline comments.")
    public String request_pr_review(
            @ToolParam(
                            name = "pr_url",
                            description =
                                    "Full GitHub PR URL (e.g."
                                            + " https://github.com/owner/repo/pull/123)")
                    String prUrl) {
        if (prUrl == null || prUrl.isBlank()) {
            return "Error: pr_url is required";
        }
        try {
            reviewerDispatcher.accept(prUrl.trim());
            return "Review requested for "
                    + prUrl.trim()
                    + ". The reviewer agent will analyze the PR and post inline comments shortly.";
        } catch (Exception e) {
            return "Error dispatching reviewer: " + e.getMessage();
        }
    }
}

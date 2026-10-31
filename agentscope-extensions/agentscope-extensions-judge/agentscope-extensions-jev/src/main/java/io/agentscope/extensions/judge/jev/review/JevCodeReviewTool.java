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

package io.agentscope.extensions.judge.jev.review;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.util.Objects;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/** An explicit read-only application tool. Host lookup, never model-controlled filesystem access. */
public final class JevCodeReviewTool {
    public record Source(BiFunction<RuntimeContext, String, Mono<JevReviewInput>> loadAuthorized) {
        public Source {
            Objects.requireNonNull(loadAuthorized);
        }
    }

    private final JevCodeReviewer reviewer;

    public JevCodeReviewTool(JevCodeReviewer reviewer) {
        this.reviewer = Objects.requireNonNull(reviewer);
    }

    @Tool(
            name = "review_code_snapshot",
            readOnly = true,
            description =
                    "Review a host-authorized code snapshot. Returns evidence-bound review"
                            + " suggestions, coverage gaps and errors, never approval or proof that"
                            + " code is defect-free. Does not edit or publish.")
    public Mono<JevExecution.Decision<JevCodeReviewer.Report>> review(
            @ToolParam(
                            name = "snapshotId",
                            description =
                                    "Identifier of an authorized snapshot, not a filesystem path")
                    String snapshotId,
            RuntimeContext context) {
        return reviewer.review(
                context,
                () -> {
                    if (context == null
                            || context.getUserId() == null
                            || context.getUserId().isBlank()
                            || context.getSessionId() == null
                            || context.getSessionId().isBlank())
                        return Mono.error(new IllegalArgumentException("REVIEW_SCOPE_REQUIRED"));
                    if (snapshotId == null
                            || snapshotId.isBlank()
                            || snapshotId.length() > 512
                            || snapshotId.chars().anyMatch(Character::isISOControl))
                        return Mono.error(new IllegalArgumentException("INVALID_SNAPSHOT_ID"));
                    var source = context.get(Source.class);
                    if (source == null)
                        return Mono.error(new IllegalStateException("REVIEW_SOURCE_REQUIRED"));
                    return source.loadAuthorized().apply(context, snapshotId);
                });
    }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * Failed background subagent tasks must be announced as failures, not with the completion wording
 * used for delivered results: the parent model otherwise treats the run as an empty business
 * result and closes the loop instead of retrying or escalating the execution failure.
 */
class SubagentTaskSettledHintTest {

    @Test
    void failedTaskIsAnnouncedWithErrorNotCompletion() {
        String hint =
                SubagentsMiddleware.taskSettledHintContent(
                        "task_1", "image-reader", TaskStatus.FAILED, null, "boom: rate limited");

        assertTrue(hint.contains("FAILED"), hint);
        assertTrue(hint.contains("boom: rate limited"), hint);
        assertTrue(hint.contains("execution failure, not a business conclusion"), hint);
        assertFalse(hint.contains("has completed"), hint);
        assertFalse(hint.contains("Result:"), hint);
    }

    @Test
    void completedTaskKeepsCompletionWording() {
        String hint =
                SubagentsMiddleware.taskSettledHintContent(
                        "task_1", "image-reader", TaskStatus.COMPLETED, "done: 3 findings", null);

        assertTrue(hint.contains("has completed"), hint);
        assertTrue(hint.contains("done: 3 findings"), hint);
        assertFalse(hint.contains("FAILED"), hint);
    }

    @Test
    void failedTaskWithoutMessageStillSaysFailed() {
        String hint =
                SubagentsMiddleware.taskSettledHintContent(
                        "task_1", "worker", TaskStatus.FAILED, null, null);

        assertTrue(hint.contains("FAILED"), hint);
        assertTrue(hint.contains("(no error message)"), hint);
    }
}

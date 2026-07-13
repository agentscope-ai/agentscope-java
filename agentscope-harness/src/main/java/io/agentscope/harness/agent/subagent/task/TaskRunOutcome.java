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
package io.agentscope.harness.agent.subagent.task;

import java.util.Objects;

/** Typed result of a local subagent task execution or permission continuation. */
public sealed interface TaskRunOutcome {

    /** The child turn completed normally. */
    record Completed(String result) implements TaskRunOutcome {}

    /** The child turn paused and must be resumed after a permission decision. */
    record WaitingForApproval(TaskSuspension suspension) implements TaskRunOutcome {
        public WaitingForApproval {
            Objects.requireNonNull(suspension, "suspension must not be null");
        }
    }

    /** The user denied the pending operation and the task is terminal. */
    record Denied(String result) implements TaskRunOutcome {}
}

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
package io.agentscope.core.state;

/** Trusted application version, captured before execution; not inferred from tool output. */
public record EvidenceBinding(String taskId, long contractVersion, String subjectVersion) {
    public EvidenceBinding {
        TaskRequirement.requireText(taskId, "task ID", 200);
        if (contractVersion < 0) throw new IllegalArgumentException("Negative contract version");
        TaskRequirement.requireText(subjectVersion, "subject version", 2000);
    }
}

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
package io.agentscope.core.message;

/**
 * Producer-supplied execution facts, separate from model-visible text and task verification.
 * An absent exit code means unknown, never zero. A successful process is not a verified task.
 */
public record ToolExecutionDetails(
        String kind, Outcome outcome, Integer exitCode, boolean outputTruncated) {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        PARTIAL,
        UNKNOWN
    }

    public ToolExecutionDetails {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind is required");
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
    }
}

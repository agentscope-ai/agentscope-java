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
package io.agentscope.harness.agent.verification;

import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.state.TaskVerification.Outcome;
import io.agentscope.harness.agent.observation.StoredActionObservation;
import java.util.Objects;

/**
 * Checks a caller-selected shell attempt, not arbitrary task semantics.
 * The caller must authorize which action implements the acceptance criterion.
 */
public final class ShellExitCodeVerifier implements DeterministicVerifier {
    private final String toolName;
    private final int expectedExitCode;

    public ShellExitCodeVerifier(String toolName, int expectedExitCode) {
        if (toolName == null || toolName.isBlank() || toolName.length() > 128)
            throw new IllegalArgumentException("Bounded tool name required");
        this.toolName = toolName;
        this.expectedExitCode = expectedExitCode;
    }

    @Override
    public String id() {
        return "shell-exit-v1:" + toolName + ":" + expectedExitCode;
    }

    @Override
    public Verdict verify(StoredActionObservation evidence) {
        var observation = evidence.observation();
        var result = evidence.result();
        var details = observation.executionDetails();
        if (!toolName.equals(observation.toolName())
                || result == null
                || result.isSuspended()
                || result.getState() == ToolResultState.DENIED
                || result.getState() == ToolResultState.INTERRUPTED
                || details == null
                || !"shell".equals(details.kind())
                || !Objects.equals(observation.toolCallId(), result.getId())
                || !Objects.equals(observation.toolName(), result.getName())
                || !Objects.equals(details, result.getExecutionDetails())) {
            return new Verdict(Outcome.UNKNOWN, "Missing or inconsistent typed shell evidence");
        }
        if (observation.status() != ActionObservation.Status.RETURNED
                && observation.status() != ActionObservation.Status.FAILED) {
            return new Verdict(Outcome.UNKNOWN, "Action did not settle with a process result");
        }
        if (details.exitCode() == null
                || details.outcome() == ToolExecutionDetails.Outcome.UNKNOWN
                || details.outcome() == ToolExecutionDetails.Outcome.PARTIAL) {
            return new Verdict(Outcome.UNKNOWN, "Process outcome is incomplete");
        }
        if ((details.exitCode() == 0)
                != (details.outcome() == ToolExecutionDetails.Outcome.SUCCEEDED)) {
            return new Verdict(Outcome.UNKNOWN, "Inconsistent exit code and producer outcome");
        }
        if (details.exitCode() == 0
                && (observation.status() == ActionObservation.Status.FAILED
                        || result.getState() == ToolResultState.ERROR)) {
            return new Verdict(
                    Outcome.UNKNOWN, "Tool failure contradicts the reported successful process");
        }
        if (details.exitCode() != expectedExitCode) {
            return new Verdict(
                    Outcome.FAILED, "Exit code did not match the configured expectation");
        }
        if (details.outputTruncated()) {
            return new Verdict(Outcome.UNKNOWN, "Captured output is truncated");
        }
        return new Verdict(
                Outcome.PASSED,
                "Selected shell attempt returned the configured exit code; no broader semantic"
                        + " assertion");
    }
}

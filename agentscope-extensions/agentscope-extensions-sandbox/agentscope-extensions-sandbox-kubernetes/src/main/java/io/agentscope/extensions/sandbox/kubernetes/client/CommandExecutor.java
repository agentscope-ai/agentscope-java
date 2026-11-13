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
package io.agentscope.extensions.sandbox.kubernetes.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Executes commands inside a sandbox via {@code POST /execute}. */
public class CommandExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int CAPABILITY_MAX_ATTEMPTS = 3;

    /**
     * Floor for the capability probe's budgets. The probe is the gate for every command, and
     * the configured per-attempt transport timeout is tuned for command exchanges; on a loaded
     * host a healthy runtime's loopback probe can transiently exceed sub-second budgets
     * (observed on a windows CI runner under -T1C, #4058: the first attempt hit its 199ms
     * header timeout and the same 200ms figure, doubling as the shared exchange deadline,
     * skipped the remaining retries), hard-failing commands whose runtime was fine. The floor
     * also lets the intended retry attempts actually run. It only widens the probe's failure
     * deadline; command exchanges keep the configured budget.
     */
    private static final Duration CAPABILITY_PROBE_MIN_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    private final SandboxConnector connector;

    public CommandExecutor(SandboxConnector connector) {
        this.connector = connector;
    }

    /**
     * Runs a command with the default timeout of 60 seconds.
     *
     * @param command shell command
     * @return execution result
     */
    public ExecutionResult run(String command) {
        return run(command, DEFAULT_TIMEOUT);
    }

    /**
     * Runs a command with the given timeout. The runtime must advertise support for the timeout
     * contract before the command is submitted. Once submission starts, any HTTP failure or
     * malformed response is reported as an unknown execution outcome because the remote process
     * may have started.
     *
     * @param command shell command
     * @param timeout positive whole-second deadline for queueing and command execution; null uses
     *     the default timeout
     * @return execution result
     */
    public ExecutionResult run(String command, Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        validateTimeout(effectiveTimeout);

        Duration requestTimeout = executionRequestTimeout(effectiveTimeout);
        boolean submissionAttempted = false;
        try {
            String json =
                    MAPPER.writeValueAsString(
                            Map.of(
                                    "command",
                                    command,
                                    "timeout_seconds",
                                    effectiveTimeout.toSeconds()));
            HttpRequest.BodyPublisher body =
                    HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
            submissionAttempted = true;
            HttpResponse<String> response =
                    connector.sendRequest(
                            "POST",
                            "execute",
                            body,
                            "application/json",
                            1,
                            requestTimeout,
                            requestTimeout);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unknownOutcome(
                        "Execute response failed (status="
                                + response.statusCode()
                                + "). Execution outcome is unknown; inspect command state before"
                                + " retrying. "
                                + truncate(response.body(), 256),
                        response.statusCode(),
                        null);
            }

            JsonNode result = MAPPER.readTree(response.body());
            if (result == null
                    || !result.path("exit_code").isIntegralNumber()
                    || !result.path("exit_code").canConvertToInt()
                    || !result.path("stdout").isTextual()
                    || !result.path("stderr").isTextual()) {
                throw new IllegalStateException("Invalid execute response");
            }
            return MAPPER.treeToValue(result, ExecutionResult.class);
        } catch (SandboxRequestException e) {
            if (submissionAttempted && !e.isExecutionOutcomeUnknown()) {
                throw unknownOutcome(e.getMessage(), e.getStatusCode(), e);
            }
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SandboxRequestException(
                    submissionAttempted
                            ? "Execution outcome unknown: the sandbox HTTP response was not"
                                    + " received or was invalid. The command may still be running;"
                                    + " inspect its state before retrying. "
                                    + e.getMessage()
                            : "Failed to prepare command: " + e.getMessage(),
                    null,
                    e,
                    submissionAttempted);
        }
    }

    private Duration executionRequestTimeout(Duration timeout) {
        try {
            Duration probeBudget = connector.getPerAttemptTimeout();
            if (probeBudget.compareTo(CAPABILITY_PROBE_MIN_ATTEMPT_TIMEOUT) < 0) {
                probeBudget = CAPABILITY_PROBE_MIN_ATTEMPT_TIMEOUT;
            }
            HttpResponse<String> response =
                    connector.sendRequest(
                            "GET",
                            "capabilities",
                            null,
                            "application/json",
                            CAPABILITY_MAX_ATTEMPTS,
                            probeBudget,
                            probeBudget);
            if (response.statusCode() != 200) {
                throw new SandboxRequestException.RuntimeCapabilityException(
                        "Runtime does not advertise execution timeout support; command was not"
                                + " submitted. Upgrade the sandbox runtime (status="
                                + response.statusCode()
                                + ").",
                        response.statusCode(),
                        null);
            }

            JsonNode capabilities = MAPPER.readTree(response.body());
            JsonNode execute =
                    capabilities == null ? MAPPER.nullNode() : capabilities.path("execute");
            JsonNode maximum = execute.path("max_timeout_seconds");
            JsonNode cleanup = execute.path("cleanup_timeout_seconds");
            if (!execute.path("timeout_seconds").isBoolean()
                    || !execute.path("timeout_seconds").booleanValue()
                    || !maximum.isIntegralNumber()
                    || !maximum.canConvertToInt()
                    || maximum.intValue() <= 0
                    || !cleanup.isIntegralNumber()
                    || !cleanup.canConvertToInt()
                    || cleanup.intValue() < 0) {
                throw new SandboxRequestException.RuntimeCapabilityException(
                        "Runtime execution timeout capabilities are invalid; command was not"
                                + " submitted.",
                        null,
                        null);
            }
            if (timeout.toSeconds() > maximum.intValue()) {
                throw new SandboxRequestException(
                        "Requested timeout exceeds runtime maximum of "
                                + maximum.intValue()
                                + " seconds; command was not submitted.",
                        422);
            }
            return timeout.plusSeconds(cleanup.intValue()).plus(connector.getPerAttemptTimeout());
        } catch (SandboxRequestException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SandboxRequestException.RuntimeCapabilityException(
                    "Cannot verify runtime execution timeout support; command was not submitted: "
                            + e.getMessage(),
                    null,
                    e);
        }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.getNano() != 0) {
            throw new IllegalArgumentException(
                    "timeout must be a positive whole number of seconds");
        }
    }

    private static SandboxRequestException unknownOutcome(
            String message, Integer statusCode, Throwable cause) {
        return new SandboxRequestException(message, statusCode, cause, true);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}

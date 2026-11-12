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
package io.agentscope.durable.examples.queuedsandboxhands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Kind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import reactor.test.StepVerifier;

class QueuedSandboxHandsExampleTest {
    private static final String TEST_TURN_ID = "test-turn";

    @Test
    void springConfigurationBindsRoleIdentityStorageAndMqIsolation() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "durable.queued-sandbox-hands.role", "worker",
                                "durable.queued-sandbox-hands.endpoint", "broker:8081",
                                "durable.queued-sandbox-hands.data-directory", "worker-data",
                                "durable.queued-sandbox-hands.namespace", "tenant",
                                "durable.queued-sandbox-hands.session-id", "sandbox-session",
                                "durable.queued-sandbox-hands.request-id", "request-2",
                                "durable.queued-sandbox-hands.timeout", "60s",
                                "durable.queued-sandbox-hands.mq-namespace", "isolated-sandbox"));
        var properties =
                new Binder(source)
                        .bind(
                                "durable.queued-sandbox-hands",
                                Bindable.of(QueuedSandboxHandsExampleProperties.class))
                        .orElseThrow(() -> new AssertionError("Example properties did not bind"));

        new QueuedSandboxHandsExample(properties).validateConfiguration();
        assertThat(properties.getRole()).isEqualTo(QueuedSandboxHandsExampleProperties.Role.WORKER);
        assertThat(properties.getEndpoint()).isEqualTo("broker:8081");
        assertThat(properties.getDataDirectory()).isEqualTo(Path.of("worker-data"));
        assertThat(properties.getNamespace()).isEqualTo("tenant");
        assertThat(properties.getSessionId()).isEqualTo("sandbox-session");
        assertThat(properties.getRequestId()).isEqualTo("request-2");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getMqNamespace()).isEqualTo("isolated-sandbox");
    }

    @Test
    void defaultsSeparateBrainAndWorkerDataAndRejectInvalidConfiguration() {
        var properties = new QueuedSandboxHandsExampleProperties();
        var brainDirectory = properties.getDataDirectory();
        assertThat(properties.getMqNamespace()).isEqualTo("durable-sandbox");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        properties.setRole(QueuedSandboxHandsExampleProperties.Role.WORKER);
        assertThat(properties.getDataDirectory()).isNotEqualTo(brainDirectory);
        var example = new QueuedSandboxHandsExample(properties);
        example.validateConfiguration();

        properties.setMqNamespace("invalid/topic");
        assertThatThrownBy(example::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
        properties.setMqNamespace("durable-sandbox");
        properties.setTimeout(Duration.ZERO);
        assertThatThrownBy(example::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
        properties.setTimeout(Duration.ofSeconds(60));
        properties.setEndpoint(" ");
        assertThatThrownBy(example::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerRequiresFrozenToolBindingItsSessionAndOnlyTheFixedCommand() {
        var example = new QueuedSandboxHandsExample(new QueuedSandboxHandsExampleProperties());
        var tool = QueuedSandboxHandsExample.tool();
        var call =
                tool.call(
                        "sandbox-1",
                        DurableJson.value(QueuedSandboxHandsExample.commandArguments()));
        var request = request("demo", "conversation", call);
        for (var kind : Kind.values()) example.authorize(ExecutionMessages.command(kind, request));
        assertThat(example.resourceBinding().namespace()).isEqualTo("demo");
        assertThat(example.resourceBinding().id()).isEqualTo("conversation");
        assertThat(example.resourceBinding().version()).isEqualTo("v1");
        assertThat(tool.target().path("operation").asText()).isEqualTo("sandbox.exec");

        assertThatThrownBy(
                        () ->
                                example.authorize(
                                        ExecutionMessages.command(
                                                Kind.EXECUTE,
                                                request("other", "conversation", call))))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(
                        () ->
                                example.authorize(
                                        ExecutionMessages.command(
                                                Kind.EXECUTE, request("demo", "other", call))))
                .isInstanceOf(SecurityException.class);
        var unbound = new ToolCall(call.callId(), call.executor(), call.name(), call.arguments());
        assertThatThrownBy(
                        () ->
                                example.authorize(
                                        ExecutionMessages.command(
                                                Kind.EXECUTE,
                                                request("demo", "conversation", unbound))))
                .isInstanceOf(DurableException.class)
                .hasMessageContaining("binding");
        var changedTool =
                new ToolCall(call.callId(), "hands", "echo", call.arguments(), tool.digest());
        assertThatThrownBy(
                        () ->
                                example.authorize(
                                        ExecutionMessages.command(
                                                Kind.EXECUTE,
                                                request("demo", "conversation", changedTool))))
                .isInstanceOf(DurableException.class);
        var arbitrary =
                tool.call(
                        "sandbox-1",
                        DurableJson.value(Map.of("command", "rm -rf /", "timeout_seconds", 30)));
        assertThatThrownBy(
                        () ->
                                example.authorize(
                                        ExecutionMessages.command(
                                                Kind.EXECUTE,
                                                request("demo", "conversation", arbitrary))))
                .isInstanceOf(SecurityException.class);
        var changedTimeout =
                tool.call(
                        "sandbox-1",
                        DurableJson.value(
                                Map.of(
                                        "command",
                                        QueuedSandboxHandsExample.COMMAND,
                                        "timeout_seconds",
                                        300)));
        assertThatThrownBy(
                        () ->
                                example.authorize(
                                        ExecutionMessages.command(
                                                Kind.EXECUTE,
                                                request("demo", "conversation", changedTimeout))))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void offlineModelEmitsTheProductionToolCallAndAcceptsOnlyRecoveredJsonSuccess() {
        var model = QueuedSandboxHandsExample.offlineModel(TEST_TURN_ID);
        var first = model.stream(List.of(), List.of(), null).blockFirst();
        assertThat(first).isNotNull();
        assertThat(first.getContent()).hasSize(1);
        var call = (ToolUseBlock) first.getContent().get(0);
        assertThat(call.getId()).isEqualTo("sandbox-" + TEST_TURN_ID);
        assertThat(call.getName()).isEqualTo("sandbox.exec");
        assertThat(call.getInput()).isEqualTo(QueuedSandboxHandsExample.commandArguments());
        var completed =
                model.stream(
                                result(
                                        TEST_TURN_ID,
                                        ToolResultState.SUCCESS,
                                        "{\"output\":\"recovered\"}"),
                                List.of(),
                                null)
                        .blockFirst();
        assertThat(completed).isNotNull();
        assertThat(((TextBlock) completed.getContent().get(0)).getText())
                .isEqualTo(QueuedSandboxHandsExample.FINAL_ANSWER);
    }

    @Test
    void previousTurnSuccessStillEmitsCurrentTurnToolCallInsteadOfSuccess() {
        var model = QueuedSandboxHandsExample.offlineModel(TEST_TURN_ID);
        var response =
                model.stream(
                                result(
                                        "old-turn",
                                        ToolResultState.SUCCESS,
                                        "{\"output\":\"recovered\"}"),
                                List.of(),
                                null)
                        .blockFirst();
        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0)).isInstanceOf(ToolUseBlock.class);
        var call = (ToolUseBlock) response.getContent().get(0);
        assertThat(call.getId()).isEqualTo("sandbox-" + TEST_TURN_ID);
        assertThat(call.getName()).isEqualTo("sandbox.exec");
        assertThat(call.getInput()).isEqualTo(QueuedSandboxHandsExample.commandArguments());
    }

    @Test
    void previousTurnSuccessCannotHideCurrentTurnFailure() {
        var messages =
                List.of(
                        result("old-turn", ToolResultState.SUCCESS, "{\"output\":\"recovered\"}")
                                .get(0),
                        result(TEST_TURN_ID, ToolResultState.ERROR, "{\"output\":\"recovered\"}")
                                .get(0));
        StepVerifier.create(
                        QueuedSandboxHandsExample.offlineModel(TEST_TURN_ID).stream(
                                messages, List.of(), null))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getMessage().contains("successful recovered"))
                .verify();
    }

    @ParameterizedTest
    @EnumSource(
            value = ToolResultState.class,
            names = {"ERROR", "INTERRUPTED", "DENIED", "RUNNING"})
    void failedOrUnfinishedToolCannotProduceSuccessEvenWithRecoveredOutput(ToolResultState state) {
        StepVerifier.create(
                        QueuedSandboxHandsExample.offlineModel(TEST_TURN_ID).stream(
                                result(TEST_TURN_ID, state, "{\"output\":\"recovered\"}"),
                                List.of(),
                                null))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getMessage().contains("successful recovered"))
                .verify();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"recovered", "not-json", "{}", "{\"output\":\"wrong\"}", "{\"output\":7}"})
    void successStateWithoutTheActualRecoveredJsonOutputIsRejected(String output) {
        StepVerifier.create(
                        QueuedSandboxHandsExample.offlineModel(TEST_TURN_ID).stream(
                                result(TEST_TURN_ID, ToolResultState.SUCCESS, output),
                                List.of(),
                                null))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void failedAndCancelledObservationsWakeBrainButNonterminalEventsDoNot() {
        assertThat(QueuedSandboxHandsExample.terminalEvent("turn.succeeded")).isTrue();
        assertThat(QueuedSandboxHandsExample.terminalEvent("turn.failed")).isTrue();
        assertThat(QueuedSandboxHandsExample.terminalEvent("turn.cancelled")).isTrue();
        assertThat(QueuedSandboxHandsExample.terminalEvent("turn.cancel_requested")).isFalse();
        assertThat(QueuedSandboxHandsExample.terminalEvent("tool.result")).isFalse();
    }

    private static ExecutionRequest request(String namespace, String sessionId, ToolCall call) {
        return new ExecutionRequest(
                "execution",
                new ThreadKey(new SessionKey(namespace, sessionId), "main"),
                "turn",
                call);
    }

    private static List<Msg> result(String turnId, ToolResultState state, String output) {
        var result =
                new ToolResultBlock(
                        "sandbox-" + turnId,
                        "sandbox.exec",
                        List.of(TextBlock.builder().text(output).build()),
                        Map.of(),
                        state);
        return List.of(Msg.builder().role(MsgRole.TOOL).content(result).build());
    }
}

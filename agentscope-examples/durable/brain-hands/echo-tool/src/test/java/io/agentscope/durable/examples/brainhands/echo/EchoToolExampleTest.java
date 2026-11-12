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
package io.agentscope.durable.examples.brainhands.echo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionState;
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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.test.StepVerifier;

class EchoToolExampleTest {
    @Test
    void validatesConfigurationAndItsExecutorContract() {
        var properties = new EchoToolProperties();
        properties.setTimeout(Duration.ofSeconds(10));
        properties.validate();
        var request = request("demo", "echo");
        var executor = EchoHandsExample.echoExecutor();
        var result = executor.startExecution(request);

        assertThat(result.state()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(result.handle()).isEqualTo("execution");
        assertThat(result.result().asText()).isEqualTo("echo: {}");
        assertThat(executor.query(request, "execution").state())
                .isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(executor.cancel(request, "execution").state())
                .isEqualTo(ExecutionState.CANCELLED);
        assertThat(executor.capabilities())
                .isEqualTo(new io.agentscope.durable.Executor.Capabilities(true, true, true));

        properties.setEndpoint(" ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RocketMQ");
        properties.setEndpoint("localhost:8081");
        properties.setTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springConfigurationBindsEndpoint() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of("durable.echo-tool.endpoint", "broker:8081"));
        var properties =
                new Binder(source)
                        .bind("durable.echo-tool", Bindable.of(EchoToolProperties.class))
                        .orElseThrow(() -> new AssertionError("Example properties did not bind"));
        assertThat(properties.getEndpoint()).isEqualTo("broker:8081");
    }

    @Test
    void applicationContextsImportOnlyTheirOwnRunnerWithoutStartingMq() {
        // ApplicationContextRunner does not invoke ApplicationRunner.run().
        new ApplicationContextRunner()
                .withUserConfiguration(EchoBrainApplication.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(EchoBrainExample.class)
                                    .hasSingleBean(EchoToolProperties.class)
                                    .doesNotHaveBean(EchoHandsExample.class)
                                    .doesNotHaveBean(EchoHandsApplication.class);
                            assertThat(context.getBean(EchoBrainExample.class).dataDirectory())
                                    .isEqualTo(Path.of("durable-echo-tool-data", "brain"));
                        });
        new ApplicationContextRunner()
                .withUserConfiguration(EchoHandsApplication.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(EchoHandsExample.class)
                                    .hasSingleBean(EchoToolProperties.class)
                                    .doesNotHaveBean(EchoBrainExample.class)
                                    .doesNotHaveBean(EchoBrainApplication.class);
                            assertThat(context.getBean(EchoHandsExample.class).dataDirectory())
                                    .isEqualTo(Path.of("durable-echo-tool-data", "hands"));
                        });
    }

    @Test
    void handsAuthorizesItsNamespaceAndEchoForEveryCommandKind() {
        var hands = new EchoHandsExample(new EchoToolProperties());
        for (var kind : Kind.values()) {
            hands.authorize(ExecutionMessages.command(kind, request("demo", "echo")));
            assertThatThrownBy(
                            () ->
                                    hands.authorize(
                                            ExecutionMessages.command(
                                                    kind, request("other", "echo"))))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(
                            () ->
                                    hands.authorize(
                                            ExecutionMessages.command(
                                                    kind, request("demo", "other"))))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void previousTurnOrDifferentToolSuccessCannotReplaceCurrentEchoCall() {
        var model = EchoBrainExample.offlineModel("current");
        for (var messages :
                List.of(
                        List.<Msg>of(),
                        result("old", "echo", ToolResultState.SUCCESS),
                        result("current", "other", ToolResultState.SUCCESS))) {
            var response = model.stream(messages, List.of(), null).blockFirst();
            assertThat(response).isNotNull();
            assertThat(response.getContent()).hasSize(1);
            var call = (ToolUseBlock) response.getContent().get(0);
            assertThat(call.getId()).isEqualTo("echo-current");
            assertThat(call.getName()).isEqualTo("echo");
            assertThat(call.getInput()).isEmpty();
        }
        var response =
                model.stream(result("current", "echo", ToolResultState.SUCCESS), List.of(), null)
                        .blockFirst();
        assertThat(response).isNotNull();
        assertThat(((TextBlock) response.getContent().get(0)).getText())
                .isEqualTo(EchoBrainExample.FINAL_ANSWER);
    }

    @ParameterizedTest
    @EnumSource(
            value = ToolResultState.class,
            names = {"ERROR", "INTERRUPTED", "DENIED", "RUNNING"})
    void oldSuccessCannotHideCurrentTurnFailure(ToolResultState state) {
        var messages =
                List.of(
                        result("old", "echo", ToolResultState.SUCCESS).get(0),
                        result("current", "echo", state).get(0));
        StepVerifier.create(
                        EchoBrainExample.offlineModel("current").stream(messages, List.of(), null))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getMessage().contains("did not return success"))
                .verify();
    }

    @Test
    void terminalObservationsWakeBrainOnlyForSettledTurns() {
        assertThat(EchoBrainExample.terminalEvent("turn.succeeded")).isTrue();
        assertThat(EchoBrainExample.terminalEvent("turn.failed")).isTrue();
        assertThat(EchoBrainExample.terminalEvent("turn.cancelled")).isTrue();
        assertThat(EchoBrainExample.terminalEvent("tool.result")).isFalse();
    }

    private static ExecutionRequest request(String namespace, String tool) {
        return new ExecutionRequest(
                "execution",
                new ThreadKey(new SessionKey(namespace, "session"), "main"),
                "turn",
                new ToolCall("call", "hands", tool, DurableJson.value(Map.of())));
    }

    private static List<Msg> result(String turnId, String tool, ToolResultState state) {
        var result =
                new ToolResultBlock(
                        "echo-" + turnId,
                        tool,
                        List.of(TextBlock.builder().text("echo: {}").build()),
                        Map.of(),
                        state);
        return List.of(Msg.builder().role(MsgRole.TOOL).content(result).build());
    }
}

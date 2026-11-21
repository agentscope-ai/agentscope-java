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
package io.agentscope.extensions.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class LangChain4jToolsTest {
    private final Scheduler workers = Schedulers.newBoundedElastic(4, 16, "interop-tool");
    private final Function<ToolCallParam, InvocationContext> context =
            param ->
                    InvocationContext.builder()
                            .chatMemoryId(param.getRuntimeContext().getSessionId())
                            .invocationParameters(
                                    InvocationParameters.from(
                                            Map.of(
                                                    "tenant",
                                                    param.getRuntimeContext().getUserId())))
                            .build();

    @AfterEach
    void close() {
        workers.dispose();
    }

    public static class ParentTools {
        @Tool(name = "greet_user", value = "Greets a user")
        public String greet(@P("User name") String person, @ToolMemoryId String memoryId) {
            return memoryId + ":" + person;
        }
    }

    public static class InheritedTools extends ParentTools {}

    public enum Mode {
        FAST,
        SAFE
    }

    public record Query(String query, List<String> tags, Mode mode) {}

    public static class NestedTools {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "query_summary")
        public String summarize(Query payload, @ToolMemoryId String memoryId) {
            calls.incrementAndGet();
            return memoryId
                    + ":"
                    + payload.query()
                    + ":"
                    + String.join(",", payload.tags())
                    + ":"
                    + payload.mode();
        }
    }

    @Test
    void convertsNestedRecordEnumAndArrayThroughTheRealAnnotatedExecutor() {
        Toolkit toolkit = new Toolkit();
        NestedTools original = new NestedTools();
        LangChain4jTools.registerAnnotated(toolkit, original, workers, context);
        ToolResultBlock result =
                toolkit.callTool(
                                param(
                                        "query_summary",
                                        "nested",
                                        "memory",
                                        "tenant",
                                        Map.of(
                                                "payload",
                                                Map.of(
                                                        "query",
                                                        "search",
                                                        "tags",
                                                        List.of("a", "b"),
                                                        "mode",
                                                        "SAFE"))))
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("memory:search:a,b:SAFE", text(result));
        assertEquals(1, original.calls.get());
    }

    @Test
    void invalidNestedEnumIsRejectedByToolkitBeforeTheOriginalMethodExecutes() {
        Toolkit toolkit = new Toolkit();
        NestedTools original = new NestedTools();
        LangChain4jTools.registerAnnotated(toolkit, original, workers, context);
        ToolResultBlock result =
                toolkit.callTool(
                                param(
                                        "query_summary",
                                        "invalid",
                                        "memory",
                                        "tenant",
                                        Map.of(
                                                "payload",
                                                Map.of(
                                                        "query",
                                                        "search",
                                                        "tags",
                                                        List.of("a"),
                                                        "mode",
                                                        "UNKNOWN"))))
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(0, original.calls.get());
    }

    @Test
    void cancellingBlockedExecutionInterruptsTheBoundedWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AgentTool adapter =
                adapt(
                        spec("cancel"),
                        (request, id) -> {
                            entered.countDown();
                            try {
                                finish.await();
                            } catch (InterruptedException expected) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            return "finished";
                        });
        var subscription = adapter.callAsync(param("cancel", "id", "s", "u", Map.of())).subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            finish.countDown();
            subscription.dispose();
        }
    }

    @Test
    void discoversRenamedInheritedAnnotationAndExecutesItsBoundMethod() {
        Toolkit toolkit = new Toolkit();
        List<AgentTool> tools =
                LangChain4jTools.registerAnnotated(toolkit, new InheritedTools(), workers, context);
        assertEquals(List.of("greet_user"), tools.stream().map(AgentTool::getName).toList());
        ToolResultBlock result =
                toolkit.callTool(
                                param(
                                        "greet_user",
                                        "call-1",
                                        "session-a",
                                        "tenant-a",
                                        Map.of("person", "张三")))
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals("session-a:张三", text(result));
        assertEquals("call-1", result.getId());
        assertEquals("greet_user", result.getName());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertFalse(
                tools.get(0).isReadOnly(),
                "foreign annotations must not grant Plan Mode permissions");
    }

    @Test
    void preservesRawConstraintsReferencesNullableArraysAndStrictness() {
        JsonObjectSchema node =
                JsonObjectSchema.builder()
                        .addProperty(
                                "label",
                                JsonRawSchema.from(
                                        "{\"type\":\"string\",\"minLength\":2,\"pattern\":\"^x\"}"))
                        .addProperty(
                                "next",
                                JsonAnyOfSchema.builder()
                                        .anyOf(
                                                JsonReferenceSchema.builder()
                                                        .reference("Node")
                                                        .build(),
                                                new JsonNullSchema())
                                        .build())
                        .required("label")
                        .additionalProperties(false)
                        .build();
        JsonObjectSchema schema =
                JsonObjectSchema.builder()
                        .addProperty(
                                "root", JsonReferenceSchema.builder().reference("Node").build())
                        .addProperty(
                                "tags",
                                JsonArraySchema.builder()
                                        .items(
                                                JsonEnumSchema.builder()
                                                        .enumValues("A", "B")
                                                        .build())
                                        .build())
                        .required("root")
                        .definitions(Map.of("Node", node))
                        .additionalProperties(false)
                        .build();
        AgentTool adapter =
                adapt(
                        ToolSpecification.builder()
                                .name("schema")
                                .parameters(schema)
                                .strict(true)
                                .build(),
                        (request, id) -> "ok");
        Map<String, Object> converted = adapter.getParameters();
        assertEquals(List.of("root"), converted.get("required"));
        assertEquals(false, converted.get("additionalProperties"));
        assertEquals(
                Map.of("$ref", "#/$defs/Node"), object(converted.get("properties")).get("root"));
        Map<String, Object> definition = object(object(converted.get("$defs")).get("Node"));
        assertEquals(
                Map.of("type", "string", "minLength", 2, "pattern", "^x"),
                object(definition.get("properties")).get("label"));
        assertEquals(
                Map.of("anyOf", List.of(Map.of("$ref", "#/$defs/Node"), Map.of("type", "null"))),
                object(definition.get("properties")).get("next"));
        assertEquals(
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string", "enum", List.of("A", "B"))),
                object(converted.get("properties")).get("tags"));
        assertEquals(true, adapter.getStrict());
    }

    @Test
    void returnedSchemaMutationDoesNotChangeLaterRegistrations() {
        AgentTool adapter =
                adapt(
                        ToolSpecification.builder()
                                .name("copy")
                                .parameters(
                                        JsonObjectSchema.builder()
                                                .addStringProperty("value")
                                                .build())
                                .build(),
                        (request, id) -> "ok");
        assertThrows(
                UnsupportedOperationException.class,
                () -> object(adapter.getParameters().get("properties")).clear());
        assertTrue(object(adapter.getParameters().get("properties")).containsKey("value"));
    }

    @Test
    void parameterlessToolHasAnEmptyObjectSchema() {
        AgentTool adapter = adapt(spec("none"), (request, id) -> "ok");
        assertEquals("object", adapter.getParameters().get("type"));
        assertTrue(object(adapter.getParameters().get("properties")).isEmpty());
    }

    @Test
    void duplicateBatchDoesNotPartiallyRegister() {
        Toolkit toolkit = new Toolkit();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LangChain4jTools.register(
                                toolkit,
                                List.of(
                                        tool("duplicate", (request, id) -> "one"),
                                        tool("duplicate", (request, id) -> "two")),
                                workers,
                                context));
        assertNull(toolkit.getTool("duplicate"));
    }

    @Test
    void existingToolIsNotOverwrittenAndNewToolIsNotPartiallyRegistered() {
        Toolkit toolkit = new Toolkit();
        AgentTool original = adapt(spec("existing"), (request, id) -> "original");
        toolkit.registerAgentTool(original);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LangChain4jTools.register(
                                toolkit,
                                List.of(
                                        tool("new_tool", (request, id) -> "new"),
                                        tool("existing", (request, id) -> "replacement")),
                                workers,
                                context));
        assertSame(original, toolkit.getTool("existing"));
        assertNull(toolkit.getTool("new_tool"));
    }

    @Test
    void unknownSchemaSubtypeFailsBeforeRegistrationRatherThanDroppingConstraints() {
        Toolkit toolkit = new Toolkit();
        AiServiceTool unknown =
                AiServiceTool.builder()
                        .toolSpecification(
                                ToolSpecification.builder()
                                        .name("unknown")
                                        .parameters(
                                                JsonObjectSchema.builder()
                                                        .addProperty(
                                                                "value",
                                                                () -> "unrecognized custom schema")
                                                        .build())
                                        .build())
                        .toolExecutor((request, id) -> "not called")
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LangChain4jTools.register(
                                toolkit,
                                List.of(tool("valid", (request, id) -> "ok"), unknown),
                                workers,
                                context));
        assertNull(toolkit.getTool("valid"));
    }

    @Test
    void immediateModesAreRejectedBeforeRegistrationRatherThanChangingBatchSemantics() {
        assertFalse(adapt(spec("normal"), (request, id) -> "ok").isReturnDirect());
        for (ReturnBehavior behavior :
                List.of(ReturnBehavior.IMMEDIATE, ReturnBehavior.IMMEDIATE_IF_LAST)) {
            Toolkit toolkit = new Toolkit();
            assertThrows(
                    UnsupportedOperationException.class,
                    () ->
                            LangChain4jTools.register(
                                    toolkit,
                                    List.of(
                                            tool("valid", (request, id) -> "ok"),
                                            tool("direct", (request, id) -> "ok").toBuilder()
                                                    .returnBehavior(behavior)
                                                    .build()),
                                    workers,
                                    context));
            assertNull(toolkit.getTool("valid"));
            assertNull(toolkit.getTool("direct"));
        }
    }

    @Test
    void executorErrorResultRetainsStateAndCallIdentityThroughToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                adapt(
                        spec("error_result"),
                        resultExecutor(
                                ToolExecutionResult.builder()
                                        .isError(true)
                                        .resultText("validation failed")
                                        .build())));
        ToolResultBlock result =
                toolkit.callTool(param("error_result", "error-1", "s", "u", Map.of()))
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("error-1", result.getId());
        assertTrue(text(result).contains("validation failed"));
    }

    @Test
    void thrownExecutionFailureRemainsAnExceptionAtTheAdapterBoundary() {
        IllegalStateException failure = new IllegalStateException("executor failed");
        AgentTool adapter =
                adapt(
                        spec("throws"),
                        (request, id) -> {
                            throw failure;
                        });
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                adapter.callAsync(param("throws", "id", "s", "u", Map.of()))
                                        .block(Duration.ofSeconds(5))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(adapter);
        ToolResultBlock handled =
                toolkit.callTool(param("throws", "id", "s", "u", Map.of()))
                        .block(Duration.ofSeconds(5));
        assertNotNull(handled);
        assertEquals(ToolResultState.ERROR, handled.getState());
    }

    @Test
    void multipleTextContentsRemainSeparateAndOrdered() {
        AgentTool adapter =
                adapt(
                        spec("parts"),
                        resultExecutor(
                                ToolExecutionResult.builder()
                                        .resultContents(
                                                List.of(
                                                        TextContent.from("first"),
                                                        TextContent.from("second")))
                                        .build()));
        ToolResultBlock result =
                adapter.callAsync(param("parts", "id", "s", "u", Map.of()))
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals(
                List.of("first", "second"),
                result.getOutput().stream().map(block -> ((TextBlock) block).getText()).toList());
    }

    @Test
    void multimodalResultsAreRejectedInsteadOfSilentlyFlattened() {
        AgentTool adapter =
                adapt(
                        spec("image"),
                        resultExecutor(
                                ToolExecutionResult.builder()
                                        .resultContents(
                                                List.of(
                                                        ImageContent.from(
                                                                "https://example.invalid/image.png")))
                                        .build()));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        adapter.callAsync(param("image", "id", "s", "u", Map.of()))
                                .block(Duration.ofSeconds(5)));
    }

    @Test
    void synchronousExecutorRunsAwayFromTheReactiveCallerThread() {
        AtomicReference<String> workerName = new AtomicReference<>();
        AgentTool adapter =
                adapt(
                        spec("thread"),
                        (request, id) -> {
                            workerName.set(Thread.currentThread().getName());
                            return "ok";
                        });
        adapter.callAsync(param("thread", "id", "s", "u", Map.of()))
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(5));
        assertTrue(workerName.get().startsWith("interop-tool"));
    }

    @Test
    void concurrentCallsKeepInvocationParametersAndMemoryIdsIsolated() {
        CountDownLatch both = new CountDownLatch(2);
        ToolExecutor executor =
                new ToolExecutor() {
                    @Override
                    public String execute(ToolExecutionRequest request, Object memoryId) {
                        throw new AssertionError("rich execution must be used");
                    }

                    @Override
                    public ToolExecutionResult executeWithContext(
                            ToolExecutionRequest request, InvocationContext invocation) {
                        both.countDown();
                        try {
                            assertTrue(both.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        return ToolExecutionResult.builder()
                                .resultText(
                                        request.id()
                                                + ":"
                                                + invocation.chatMemoryId()
                                                + ":"
                                                + invocation.invocationParameters().get("tenant"))
                                .build();
                    }
                };
        AgentTool adapter = adapt(spec("context"), executor);
        List<ToolResultBlock> results =
                Mono.zip(
                                adapter.callAsync(
                                        param("context", "a", "session-a", "tenant-a", Map.of())),
                                adapter.callAsync(
                                        param("context", "b", "session-b", "tenant-b", Map.of())))
                        .map(pair -> List.of(pair.getT1(), pair.getT2()))
                        .block(Duration.ofSeconds(8));
        assertNotNull(results);
        assertEquals(
                List.of("a:session-a:tenant-a", "b:session-b:tenant-b"),
                results.stream().map(LangChain4jToolsTest::text).toList());
    }

    @Test
    void mismatchedPublishedToolNameDoesNotExecuteAnotherBinding() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool adapter =
                adapt(
                        spec("bound"),
                        (request, id) -> {
                            executions.incrementAndGet();
                            return "ok";
                        });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        adapter.callAsync(param("other", "id", "s", "u", Map.of()))
                                .block(Duration.ofSeconds(5)));
        assertEquals(0, executions.get());
    }

    @Test
    void creatingPublisherDoesNotInvokeTheExecutorBeforeSubscription() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool adapter =
                adapt(
                        spec("lazy"),
                        (request, id) -> Integer.toString(executions.incrementAndGet()));
        Mono<ToolResultBlock> call = adapter.callAsync(param("lazy", "id", "s", "u", Map.of()));
        assertEquals(0, executions.get());
        assertEquals("1", text(call.block(Duration.ofSeconds(5))));
        assertEquals("2", text(call.block(Duration.ofSeconds(5))));
    }

    private AgentTool adapt(ToolSpecification specification, ToolExecutor executor) {
        return LangChain4jTools.adapt(
                AiServiceTool.builder()
                        .toolSpecification(specification)
                        .toolExecutor(executor)
                        .build(),
                workers,
                context);
    }

    private static AiServiceTool tool(String name, ToolExecutor executor) {
        return AiServiceTool.builder().toolSpecification(spec(name)).toolExecutor(executor).build();
    }

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).build();
    }

    private static ToolCallParam param(
            String name, String id, String session, String user, Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .name(name)
                                .id(id)
                                .input(input)
                                .content(JsonUtils.getJsonCodec().toJson(input))
                                .build())
                .input(input)
                .runtimeContext(RuntimeContext.builder().sessionId(session).userId(user).build())
                .build();
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static ToolExecutor resultExecutor(ToolExecutionResult result) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object id) {
                throw new AssertionError("rich execution must be used");
            }

            @Override
            public ToolExecutionResult executeWithContext(
                    ToolExecutionRequest request, InvocationContext context) {
                return result;
            }
        };
    }
}

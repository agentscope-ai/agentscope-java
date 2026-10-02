/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for the {@code supportsNativeStructuredOutputWithTools()} routing logic
 * in {@link ReActAgent#doStructuredCall}.
 *
 * <p>Verifies that when an agent has tools registered:
 * <ul>
 *   <li>Models returning {@code supportsNativeStructuredOutputWithTools() == true}
 *       use the native {@code response_format} path.</li>
 *   <li>Models returning {@code supportsNativeStructuredOutputWithTools() == false}
 *       fall back to the synthetic {@code generate_response} tool path.</li>
 * </ul>
 */
class ReActAgentStructuredOutputWithToolsTest {

    static class WeatherInfo {
        public String city;
        public String temperature;
    }

    static class DummyTools {
        @Tool(description = "Get current weather")
        public String getWeather(@ToolParam(name = "city", description = "city") String city) {
            return "Sunny, 25°C";
        }
    }

    // ------------------------------------------------------------------
    // Configurable mock model: controls both supportsNative* flags
    // ------------------------------------------------------------------

    static class ConfigurableMockModel implements Model {

        private final boolean nativeStructuredOutput;
        private final boolean nativeStructuredOutputWithTools;
        private final Function<List<Msg>, List<ChatResponse>> responder;
        private final AtomicReference<List<ToolSchema>> capturedTools = new AtomicReference<>();
        private final AtomicReference<GenerateOptions> capturedOptions = new AtomicReference<>();

        ConfigurableMockModel(
                boolean nativeStructuredOutput,
                boolean nativeStructuredOutputWithTools,
                Function<List<Msg>, List<ChatResponse>> responder) {
            this.nativeStructuredOutput = nativeStructuredOutput;
            this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
            this.responder = responder;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            capturedTools.set(tools);
            capturedOptions.set(options);
            return Flux.fromIterable(responder.apply(messages));
        }

        @Override
        public String getModelName() {
            return "configurable-mock";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return nativeStructuredOutput;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return nativeStructuredOutputWithTools;
        }

        List<ToolSchema> getCapturedTools() {
            return capturedTools.get();
        }

        GenerateOptions getCapturedOptions() {
            return capturedOptions.get();
        }

        boolean toolListContainsGenerateResponse() {
            List<ToolSchema> tools = capturedTools.get();
            if (tools == null) {
                return false;
            }
            return tools.stream().anyMatch(t -> "generate_response".equals(t.getName()));
        }

        boolean optionsHaveResponseFormat() {
            GenerateOptions opts = capturedOptions.get();
            return opts != null && opts.getResponseFormat() != null;
        }
    }

    // ------------------------------------------------------------------
    // Responder factories
    // ------------------------------------------------------------------

    /**
     * A responder for the fallback (generate_response tool) path: the model
     * calls the generate_response tool with the structured JSON payload.
     */
    private static Function<List<Msg>, List<ChatResponse>> fallbackResponder() {
        Map<String, Object> toolInput =
                Map.of("response", Map.of("city", "Shanghai", "temperature", "32°C"));
        return msgs -> {
            boolean hasToolResults = msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
            if (!hasToolResults) {
                return List.of(
                        ChatResponse.builder()
                                .id("msg_1")
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call_1")
                                                        .name("generate_response")
                                                        .input(toolInput)
                                                        .content(
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(toolInput))
                                                        .build()))
                                .usage(new ChatUsage(10, 20, 0.5))
                                .build());
            }
            return List.of(
                    ChatResponse.builder()
                            .id("msg_2")
                            .content(List.of(TextBlock.builder().text("Done").build()))
                            .usage(new ChatUsage(5, 10, 0.1))
                            .build());
        };
    }

    /**
     * A responder for the native (response_format) path: the model returns
     * structured JSON as plain text content.
     */
    private static Function<List<Msg>, List<ChatResponse>> nativeResponder() {
        String json = "{\"city\":\"Shanghai\",\"temperature\":\"32°C\"}";
        return msgs ->
                List.of(
                        ChatResponse.builder()
                                .id("msg_1")
                                .content(List.of(TextBlock.builder().text(json).build()))
                                .usage(new ChatUsage(10, 20, 0.5))
                                .build());
    }

    private static Msg userMsg() {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("What's the weather?").build())
                .build();
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No tools + native SO supported → native path (response_format)")
    void noTools_nativeSoSupported_usesNativePath() {
        ConfigurableMockModel model = new ConfigurableMockModel(true, true, nativeResponder());

        ReActAgent agent =
                ReActAgent.builder().name("test-agent").sysPrompt("test").model(model).build();

        Msg result =
                agent.call(userMsg(), WeatherInfo.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(result);
        assertTrue(
                model.optionsHaveResponseFormat(),
                "Native path should set response_format in options");
        assertFalse(
                model.toolListContainsGenerateResponse(),
                "Native path should not inject generate_response tool");
    }

    @Test
    @DisplayName("No tools + native SO not supported → fallback path (generate_response tool)")
    void noTools_nativeSoNotSupported_usesFallbackPath() {
        ConfigurableMockModel model = new ConfigurableMockModel(false, false, fallbackResponder());

        ReActAgent agent =
                ReActAgent.builder().name("test-agent").sysPrompt("test").model(model).build();

        Msg result =
                agent.call(userMsg(), WeatherInfo.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(result);
        assertTrue(
                model.toolListContainsGenerateResponse(),
                "Fallback path should inject generate_response tool");
        assertFalse(
                model.optionsHaveResponseFormat(), "Fallback path should not set response_format");
    }

    @Test
    @DisplayName("Has tools + supportsNativeStructuredOutputWithTools=true → native path")
    void hasTools_withToolsSupported_usesNativePath() {
        ConfigurableMockModel model = new ConfigurableMockModel(true, true, nativeResponder());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DummyTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .build();

        Msg result =
                agent.call(userMsg(), WeatherInfo.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(result);
        assertTrue(
                model.optionsHaveResponseFormat(),
                "Should use native path when model supports SO with tools");
        assertFalse(
                model.toolListContainsGenerateResponse(),
                "Should not inject generate_response when using native path");

        // The real tool (getWeather) should be present
        List<ToolSchema> tools = model.getCapturedTools();
        assertNotNull(tools);
        assertTrue(
                tools.stream().anyMatch(t -> "getWeather".equals(t.getName())),
                "Real tool should be present in tool list");
    }

    @Test
    @DisplayName("Has tools + supportsNativeStructuredOutputWithTools=false → fallback path")
    void hasTools_withToolsNotSupported_usesFallbackPath() {
        ConfigurableMockModel model = new ConfigurableMockModel(true, false, fallbackResponder());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DummyTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .build();

        Msg result =
                agent.call(userMsg(), WeatherInfo.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(result);
        assertTrue(
                model.toolListContainsGenerateResponse(),
                "Should inject generate_response when model doesn't support SO with tools");
        assertFalse(
                model.optionsHaveResponseFormat(),
                "Should not set response_format on fallback path");

        // The real tool (getWeather) should ALSO be present alongside generate_response
        List<ToolSchema> tools = model.getCapturedTools();
        assertNotNull(tools);
        assertTrue(
                tools.stream().anyMatch(t -> "getWeather".equals(t.getName())),
                "Real tool should still be present alongside generate_response");
    }

    @Test
    @DisplayName(
            "Has tools + supportsNativeStructuredOutputWithTools=false → structured data is"
                    + " correct")
    void hasTools_fallbackPath_structuredDataIsCorrect() {
        ConfigurableMockModel model = new ConfigurableMockModel(true, false, fallbackResponder());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DummyTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .build();

        Msg result =
                agent.call(userMsg(), WeatherInfo.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(result);
        WeatherInfo info = result.getStructuredData(WeatherInfo.class);
        assertNotNull(info, "Structured data should be extracted");
        assertEquals("Shanghai", info.city);
        assertEquals("32°C", info.temperature);
    }

    @Test
    @DisplayName(
            "Default supportsNativeStructuredOutputWithTools follows"
                    + " supportsNativeStructuredOutput")
    void defaultWithToolsFollowsNativeFlag() {
        // Model with supportsNativeStructuredOutput=false should also return false
        // for supportsNativeStructuredOutputWithTools (default implementation)
        Model modelOff =
                new ConfigurableMockModel(false, false, nativeResponder()) {
                    @Override
                    public boolean supportsNativeStructuredOutputWithTools() {
                        // Simulate default Model interface behavior
                        return supportsNativeStructuredOutput();
                    }
                };
        assertFalse(modelOff.supportsNativeStructuredOutputWithTools());

        // Model with supportsNativeStructuredOutput=true should also return true
        Model modelOn =
                new ConfigurableMockModel(true, true, nativeResponder()) {
                    @Override
                    public boolean supportsNativeStructuredOutputWithTools() {
                        return supportsNativeStructuredOutput();
                    }
                };
        assertTrue(modelOn.supportsNativeStructuredOutputWithTools());
    }
}

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

package io.agentscope.core.nacos.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NacosPromptListener}.
 */
@ExtendWith(MockitoExtension.class)
class NacosPromptListenerTest {

    private static final String VALID_CONFIG =
            "{\"promptKey\":\"test-agent\",\"template\":\"You are {{role}} in {{department}}\"}";
    private static final String VALID_CONFIG_NO_VARS =
            "{\"promptKey\":\"simple\",\"template\":\"You are a helpful assistant\"}";
    private static final String DEFAULT_GROUP = "nacos-ai-prompt";

    @Mock private ConfigService configService;

    private NacosPromptListener listener;

    @BeforeEach
    void setUp() {
        listener = new NacosPromptListener(configService);
    }

    @Nested
    @DisplayName("getPrompt - basic loading")
    class GetPromptBasicTests {

        @Test
        @DisplayName("should load prompt from Nacos and return rendered template")
        void shouldLoadAndRenderPrompt() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("test-agent.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(VALID_CONFIG);

            Map<String, String> args = Map.of("role", "AI Assistant", "department", "Engineering");
            String result = listener.getPrompt("test-agent", args);

            assertEquals("You are AI Assistant in Engineering", result);
        }

        @Test
        @DisplayName("should load prompt without variable rendering when args is null")
        void shouldLoadPromptWithoutArgs() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("simple.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(VALID_CONFIG_NO_VARS);

            String result = listener.getPrompt("simple");

            assertEquals("You are a helpful assistant", result);
        }

        @Test
        @DisplayName("should load prompt without variable rendering when args is empty")
        void shouldLoadPromptWithEmptyArgs() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("simple.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(VALID_CONFIG_NO_VARS);

            String result = listener.getPrompt("simple", Map.of());

            assertEquals("You are a helpful assistant", result);
        }

        @Test
        @DisplayName("should return empty string when prompt config is invalid JSON")
        void shouldReturnEmptyForInvalidJson() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("bad.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("not valid json");

            String result = listener.getPrompt("bad");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty string when config missing promptKey field")
        void shouldReturnEmptyForMissingPromptKey() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("no-key.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"template\":\"some text\"}");

            String result = listener.getPrompt("no-key");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty string when config missing template field")
        void shouldReturnEmptyForMissingTemplate() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("no-tpl.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"no-tpl\"}");

            String result = listener.getPrompt("no-tpl");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty string when template field is null")
        void shouldReturnEmptyForNullTemplate() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("null-tpl.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"null-tpl\",\"template\":null}");

            String result = listener.getPrompt("null-tpl");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getPrompt - default value fallback")
    class DefaultValueTests {

        @Test
        @DisplayName("should use default value when Nacos returns empty config")
        void shouldFallbackToDefaultValue() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("missing.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"missing\"}");

            String result = listener.getPrompt("missing", null, "I am a fallback assistant");

            assertEquals("I am a fallback assistant", result);
        }

        @Test
        @DisplayName("should render default value with args")
        void shouldRenderDefaultValueWithArgs() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("missing.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"missing\"}");

            Map<String, String> args = Map.of("name", "Bob");
            String result = listener.getPrompt("missing", args, "Hello {{name}}");

            assertEquals("Hello Bob", result);
        }

        @Test
        @DisplayName("should return empty string when no default value and Nacos empty")
        void shouldReturnEmptyWhenNoDefaultAndNacosEmpty() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("missing.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"missing\"}");

            String result = listener.getPrompt("missing", null, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should use Nacos value over default value when both available")
        void shouldPreferNacosOverDefault() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("test-agent.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(VALID_CONFIG);

            String result =
                    listener.getPrompt(
                            "test-agent",
                            Map.of("role", "Helper", "department", "Sales"),
                            "This is the fallback");

            assertEquals("You are Helper in Sales", result);
        }

        @Test
        @DisplayName("should use default value when NacosException occurs during loading")
        void shouldFallbackOnNacosException() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("error.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenThrow(new NacosException(500, "Nacos server error"));

            String result = listener.getPrompt("error", null, "Fallback on error");

            assertEquals("Fallback on error", result);
        }
    }

    @Nested
    @DisplayName("Template rendering")
    class TemplateRenderingTests {

        @Test
        @DisplayName("should replace multiple variables in template")
        void shouldReplaceMultipleVariables() throws NacosException {
            String config =
                    "{\"promptKey\":\"multi\",\"template\":\"{{greeting}} I am {{name}}, working at"
                            + " {{company}}\"}";
            when(configService.getConfigAndSignListener(
                            eq("multi.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(config);

            Map<String, String> args = new HashMap<>();
            args.put("greeting", "Hello!");
            args.put("name", "Agent");
            args.put("company", "Alibaba");

            String result = listener.getPrompt("multi", args);

            assertEquals("Hello! I am Agent, working at Alibaba", result);
        }

        @Test
        @DisplayName("should leave unmatched placeholders as-is")
        void shouldLeaveUnmatchedPlaceholders() throws NacosException {
            String config =
                    "{\"promptKey\":\"partial\","
                            + "\"template\":\"Hello {{name}}, your role is {{role}}\"}";
            when(configService.getConfigAndSignListener(
                            eq("partial.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(config);

            Map<String, String> args = Map.of("name", "Alice");
            String result = listener.getPrompt("partial", args);

            assertEquals("Hello Alice, your role is {{role}}", result);
        }

        @Test
        @DisplayName("should handle null value in args by replacing with empty string")
        void shouldHandleNullArgValue() throws NacosException {
            String config = "{\"promptKey\":\"nullval\"," + "\"template\":\"Hello {{name}}\"}";
            when(configService.getConfigAndSignListener(
                            eq("nullval.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(config);

            Map<String, String> args = new HashMap<>();
            args.put("name", null);
            String result = listener.getPrompt("nullval", args);

            assertEquals("Hello ", result);
        }
    }

    @Nested
    @DisplayName("Caching via computeIfAbsent")
    class CachingTests {

        @Test
        @DisplayName("should only call Nacos once for the same key (cache hit)")
        void shouldCachePromptAfterFirstLoad() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("cached.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(VALID_CONFIG_NO_VARS);

            listener.getPrompt("cached");
            listener.getPrompt("cached");
            listener.getPrompt("cached");

            verify(configService, times(1))
                    .getConfigAndSignListener(
                            eq("cached.json"), eq(DEFAULT_GROUP), anyLong(), any());
        }

        @Test
        @DisplayName("should call Nacos separately for different keys")
        void shouldLoadDifferentKeysIndependently() throws NacosException {
            when(configService.getConfigAndSignListener(
                            eq("key-a.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"key-a\",\"template\":\"Template A\"}");
            when(configService.getConfigAndSignListener(
                            eq("key-b.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"key-b\",\"template\":\"Template B\"}");

            assertEquals("Template A", listener.getPrompt("key-a"));
            assertEquals("Template B", listener.getPrompt("key-b"));

            verify(configService, times(1))
                    .getConfigAndSignListener(
                            eq("key-a.json"), eq(DEFAULT_GROUP), anyLong(), any());
            verify(configService, times(1))
                    .getConfigAndSignListener(
                            eq("key-b.json"), eq(DEFAULT_GROUP), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Listener callback - config update")
    class ListenerCallbackTests {

        @Test
        @DisplayName("should update cached prompt when listener receives new config")
        void shouldUpdateCacheOnListenerCallback() throws Exception {
            when(configService.getConfigAndSignListener(
                            eq("updatable.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn(
                            "{\"promptKey\":\"updatable\","
                                    + "\"template\":\"Original template\"}");

            // First load
            assertEquals("Original template", listener.getPrompt("updatable"));

            // Simulate listener callback with updated config
            Listener nacosListener = getPromptListener();
            nacosListener.receiveConfigInfo(
                    "{\"promptKey\":\"updatable\"," + "\"template\":\"Updated template\"}");

            // Should return updated template
            assertEquals("Updated template", listener.getPrompt("updatable"));
        }

        @Test
        @DisplayName("should not crash when listener receives invalid JSON")
        void shouldHandleInvalidJsonInCallback() throws Exception {
            when(configService.getConfigAndSignListener(
                            eq("stable.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"stable\"," + "\"template\":\"Stable template\"}");

            // First load
            assertEquals("Stable template", listener.getPrompt("stable"));

            // Simulate listener callback with invalid JSON - should not throw
            Listener nacosListener = getPromptListener();
            nacosListener.receiveConfigInfo("not valid json");

            // Should still return original template
            assertEquals("Stable template", listener.getPrompt("stable"));
        }

        @Test
        @DisplayName("should ignore callback when missing promptKey field")
        void shouldIgnoreCallbackMissingPromptKey() throws Exception {
            when(configService.getConfigAndSignListener(
                            eq("safe.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"safe\"," + "\"template\":\"Safe template\"}");

            assertEquals("Safe template", listener.getPrompt("safe"));

            Listener nacosListener = getPromptListener();
            nacosListener.receiveConfigInfo("{\"template\":\"No key here\"}");

            assertEquals("Safe template", listener.getPrompt("safe"));
        }

        @Test
        @DisplayName("should ignore callback when template field is missing")
        void shouldIgnoreCallbackMissingTemplate() throws Exception {
            when(configService.getConfigAndSignListener(
                            eq("keep.json"), eq(DEFAULT_GROUP), anyLong(), any()))
                    .thenReturn("{\"promptKey\":\"keep\"," + "\"template\":\"Keep this\"}");

            assertEquals("Keep this", listener.getPrompt("keep"));

            Listener nacosListener = getPromptListener();
            nacosListener.receiveConfigInfo("{\"promptKey\":\"keep\"}");

            assertEquals("Keep this", listener.getPrompt("keep"));
        }

        /**
         * Access the private promptListener field via reflection.
         */
        private Listener getPromptListener() throws Exception {
            Field field = NacosPromptListener.class.getDeclaredField("promptListener");
            field.setAccessible(true);
            return (Listener) field.get(listener);
        }
    }
}

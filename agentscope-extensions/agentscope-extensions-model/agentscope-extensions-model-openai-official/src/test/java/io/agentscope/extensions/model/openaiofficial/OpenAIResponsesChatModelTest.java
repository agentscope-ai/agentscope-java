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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

class OpenAIResponsesChatModelTest {

    private static final String API_KEY = "test-key";
    private static final String MODEL_NAME = "gpt-4o";

    private static TextBlock text(String t) {
        return TextBlock.builder().text(t).build();
    }

    private static List<Msg> simpleMessages() {
        return List.of(UserMessage.builder().content(text("Hello")).build());
    }

    private static GenerateOptions configuredOptions(boolean stream) {
        return configuredOptions(stream, API_KEY, null);
    }

    private static GenerateOptions configuredOptions(
            boolean stream, String apiKey, String baseUrl) {
        GenerateOptions base =
                GenerateOptions.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(MODEL_NAME)
                        .stream(stream)
                        .build();
        base = ModelUtils.ensureDefaultExecutionConfig(base);
        ExecutionConfig moduleRetry =
                ExecutionConfig.builder().retryOn(OpenAIResponsesChatModel.moduleRetryOn()).build();
        ExecutionConfig mergedExec =
                ExecutionConfig.mergeConfigs(moduleRetry, base.getExecutionConfig());
        GenerateOptions execOverride =
                GenerateOptions.builder().executionConfig(mergedExec).build();
        return GenerateOptions.mergeOptions(execOverride, base);
    }

    private static OpenAIResponsesChatModel createModel(OpenAIClient client, boolean stream) {
        return createModel(client, stream, API_KEY, null);
    }

    private static OpenAIResponsesChatModel createModel(
            OpenAIClient client, boolean stream, String apiKey, String baseUrl) {
        GenerateOptions options = configuredOptions(stream, apiKey, baseUrl);
        OpenAIResponsesChatModel model =
                new OpenAIResponsesChatModel(client, options, apiKey, baseUrl, null, null, null);
        model.applyNativeStructuredOutputDefaults();
        return model;
    }

    private static OpenAIClient mockClientWithResponseService() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        when(client.responses()).thenReturn(responseService);
        return client;
    }

    @Nested
    class NonStreaming {

        @Test
        void nonStreamingEndToEnd() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("Hello world"));

            OpenAIResponsesChatModel model = createModel(client, false);
            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, null).collectList().block();

            assertNotNull(results);
            assertEquals(1, results.size());
            boolean hasText =
                    results.get(0).getContent().stream()
                            .anyMatch(
                                    b ->
                                            b instanceof TextBlock tb
                                                    && tb.getText().contains("Hello"));
            assertTrue(hasText);
        }

        @Test
        void connectionFieldMismatchApiKeyFailsFast() {
            OpenAIClient client = mockClientWithResponseService();
            OpenAIResponsesChatModel model = createModel(client, false, API_KEY, null);

            GenerateOptions perCall = GenerateOptions.builder().apiKey("different-key").build();

            assertThrows(
                    OpenAIOfficialModelException.class,
                    () -> model.stream(simpleMessages(), null, perCall).collectList().block());
        }

        @Test
        void connectionFieldMismatchBaseUrlFailsFast() {
            OpenAIClient client = mockClientWithResponseService();
            OpenAIResponsesChatModel model = createModel(client, false, API_KEY, null);

            GenerateOptions perCall =
                    GenerateOptions.builder().baseUrl("https://custom.example.com").build();

            assertThrows(
                    OpenAIOfficialModelException.class,
                    () -> model.stream(simpleMessages(), null, perCall).collectList().block());
        }

        @Test
        void perCallNullApiKeyDoesNotFailFast() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("Hello world"));

            OpenAIResponsesChatModel model = createModel(client, false, API_KEY, null);

            // per-call options with null apiKey — should fall back to configured, no fail-fast
            GenerateOptions perCall = GenerateOptions.builder().build();

            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, perCall).collectList().block();

            assertNotNull(results);
            assertEquals(1, results.size());
        }

        @Test
        void perCallNullBaseUrlDoesNotFailFast() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("Hello world"));

            OpenAIResponsesChatModel model =
                    createModel(client, false, API_KEY, "https://configured.example.com");

            // per-call options with null baseUrl — should fall back to configured, no fail-fast
            GenerateOptions perCall = GenerateOptions.builder().build();

            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, perCall).collectList().block();

            assertNotNull(results);
            assertEquals(1, results.size());
        }

        @Test
        void perCallBlankApiKeyDoesNotFailFast() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("Hello world"));

            OpenAIResponsesChatModel model = createModel(client, false, API_KEY, null);

            // blank apiKey should be treated as "not set", not as an override
            GenerateOptions perCall = GenerateOptions.builder().apiKey("   ").build();

            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, perCall).collectList().block();

            assertNotNull(results);
            assertEquals(1, results.size());
        }

        @Test
        void perCallBlankBaseUrlDoesNotFailFast() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.textResponse("Hello world"));

            OpenAIResponsesChatModel model =
                    createModel(client, false, API_KEY, "https://configured.example.com");

            // blank baseUrl should be treated as "not set", not as an override
            GenerateOptions perCall = GenerateOptions.builder().baseUrl("  ").build();

            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, perCall).collectList().block();

            assertNotNull(results);
            assertEquals(1, results.size());
        }
    }

    @Nested
    class Streaming {

        @Test
        void streamingEndToEnd() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            List<ResponseStreamEvent> events =
                    List.of(
                            TestSdkFixtures.textDeltaEvent("Hello", "msg_1"),
                            TestSdkFixtures.completedEvent(TestSdkFixtures.textResponse("Hello")));
            when(svc.createStreaming(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.streamOf(events));

            OpenAIResponsesChatModel model = createModel(client, true);
            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, null).collectList().block();

            assertNotNull(results);
            assertFalse(results.isEmpty());
            boolean hasText =
                    results.stream()
                            .flatMap(r -> r.getContent().stream())
                            .anyMatch(
                                    b ->
                                            b instanceof TextBlock tb
                                                    && tb.getText().contains("Hello"));
            assertTrue(hasText);
        }
    }

    @Nested
    class ErrorTranslation {

        @Test
        void nonStreamingSdkErrorTranslated() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenThrow(TestSdkFixtures.badRequest("Invalid request"));

            OpenAIResponsesChatModel model = createModel(client, false);

            OpenAIOfficialModelException ex =
                    assertThrows(
                            OpenAIOfficialModelException.class,
                            () -> model.stream(simpleMessages(), null, null).collectList().block());
            assertEquals(400, ex.getStatusCode());
            assertEquals(OpenAIOfficialConstants.PROVIDER_ID, ex.getProvider());
        }

        @Test
        void streamingSdkErrorTranslated() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.createStreaming(any(ResponseCreateParams.class)))
                    .thenThrow(TestSdkFixtures.badRequest("Invalid request"));

            OpenAIResponsesChatModel model = createModel(client, true);

            OpenAIOfficialModelException ex =
                    assertThrows(
                            OpenAIOfficialModelException.class,
                            () -> model.stream(simpleMessages(), null, null).collectList().block());
            assertEquals(400, ex.getStatusCode());
        }
    }

    @Nested
    class StreamDefault {

        @Test
        void builderDefaultStreamIsTrue() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            List<ResponseStreamEvent> events =
                    List.of(TestSdkFixtures.completedEvent(TestSdkFixtures.textResponse("ok")));
            when(svc.createStreaming(any(ResponseCreateParams.class)))
                    .thenReturn(TestSdkFixtures.streamOf(events));

            GenerateOptions options = configuredOptions(true);
            OpenAIResponsesChatModel model =
                    new OpenAIResponsesChatModel(client, options, API_KEY, null, null, null, null);
            model.applyNativeStructuredOutputDefaults();

            model.stream(simpleMessages(), null, null).collectList().block();

            verify(svc).createStreaming(any(ResponseCreateParams.class));
        }
    }

    @Nested
    class RetryOnPredicate {

        @Test
        void statusCode400NotRetryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.badRequest("bad"), MODEL_NAME, 400);
            assertFalse(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void statusCode429Retryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.rateLimit("rate"), MODEL_NAME, 429);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void statusCode500Retryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.internalServer(500, "server"), MODEL_NAME, 500);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void statusCode408Retryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err",
                            TestSdkFixtures.unexpectedStatusCode(408, "conflict"),
                            MODEL_NAME,
                            408);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void openAiIoExceptionRetryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.ioException("io"), MODEL_NAME);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void openAiRetryableExceptionRetryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.retryableException("retryable"), MODEL_NAME);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void timeoutExceptionRetryable() {
            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.timeoutException("timeout"), MODEL_NAME);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void reactorTimeoutModelExceptionRetryable() {
            ModelException ex =
                    new ModelException(
                            "Model request timeout",
                            new TimeoutException("Model request timeout after PT5M"),
                            MODEL_NAME,
                            "openai-official");
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void xShouldRetryTrueForcesRetryable() {
            OpenAIServiceException mockSvc = mock(OpenAIServiceException.class);
            when(mockSvc.statusCode()).thenReturn(400);
            when(mockSvc.headers()).thenReturn(TestSdkFixtures.headersWithShouldRetry("true"));

            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException("err", mockSvc, MODEL_NAME, 400);
            assertTrue(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void xShouldRetryFalseForcesNotRetryable() {
            OpenAIServiceException mockSvc = mock(OpenAIServiceException.class);
            when(mockSvc.statusCode()).thenReturn(500);
            when(mockSvc.headers()).thenReturn(TestSdkFixtures.headersWithShouldRetry("false"));

            OpenAIOfficialModelException ex =
                    new OpenAIOfficialModelException("err", mockSvc, MODEL_NAME, 500);
            assertFalse(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }

        @Test
        void plainModelExceptionNotRetryable() {
            OpenAIOfficialModelException ex = new OpenAIOfficialModelException("validation error");
            assertFalse(OpenAIResponsesChatModel.moduleRetryOn().test(ex));
        }
    }

    @Nested
    class RetryOnInjection {

        @Test
        void moduleRetryOnInjectedWhenUserDoesNotProvideCustom() {
            OpenAIResponsesChatModel model =
                    OpenAIResponsesChatModel.builder()
                            .apiKey("test-key")
                            .modelName(MODEL_NAME)
                            .build();

            GenerateOptions configured = model.getConfiguredOptions();
            assertNotNull(configured);
            ExecutionConfig execConfig = configured.getExecutionConfig();
            assertNotNull(execConfig);
            Predicate<Throwable> injectedRetryOn = execConfig.getRetryOn();
            assertNotNull(injectedRetryOn);

            // The injected retryOn should behave identically to moduleRetryOn()
            Predicate<Throwable> expected = OpenAIResponsesChatModel.moduleRetryOn();

            // Retryable: 429
            OpenAIOfficialModelException retryable =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.rateLimit("rate"), MODEL_NAME, 429);
            assertTrue(injectedRetryOn.test(retryable));
            assertEquals(expected.test(retryable), injectedRetryOn.test(retryable));

            // Non-retryable: 400
            OpenAIOfficialModelException nonRetryable =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.badRequest("bad"), MODEL_NAME, 400);
            assertFalse(injectedRetryOn.test(nonRetryable));
            assertEquals(expected.test(nonRetryable), injectedRetryOn.test(nonRetryable));
        }

        @Test
        void userProvidedRetryOnRespected() {
            Predicate<Throwable> customRetryOn = e -> false;

            GenerateOptions userOptions =
                    GenerateOptions.builder()
                            .executionConfig(
                                    ExecutionConfig.builder().retryOn(customRetryOn).build())
                            .build();

            OpenAIResponsesChatModel model =
                    OpenAIResponsesChatModel.builder()
                            .apiKey("test-key")
                            .modelName(MODEL_NAME)
                            .generateOptions(userOptions)
                            .build();

            GenerateOptions configured = model.getConfiguredOptions();
            assertNotNull(configured);
            ExecutionConfig execConfig = configured.getExecutionConfig();
            assertNotNull(execConfig);

            // The user's retryOn should be retained, not overridden by module retryOn
            assertSame(customRetryOn, execConfig.getRetryOn());

            // Behavioral verification: custom retryOn always returns false,
            // even for errors that the module retryOn would classify as retryable
            OpenAIOfficialModelException retryable =
                    new OpenAIOfficialModelException(
                            "err", TestSdkFixtures.rateLimit("rate"), MODEL_NAME, 429);
            assertFalse(execConfig.getRetryOn().test(retryable));
        }
    }

    // ── Module-level config ─────────────────────────────────────────

    @Nested
    class ModuleLevelConfig {

        @Test
        void nativeStructuredOutputAlwaysTrue() {
            OpenAIClient client = mockClientWithResponseService();
            OpenAIResponsesChatModel model = createModel(client, false);
            assertTrue(model.supportsNativeStructuredOutput());
        }

        @Test
        void nativeStructuredOutputWithToolsAlwaysTrue() {
            OpenAIClient client = mockClientWithResponseService();
            OpenAIResponsesChatModel model = createModel(client, false);
            assertTrue(model.supportsNativeStructuredOutputWithTools());
        }

        @Test
        void contextWindowSizeFromBuilderOverridesLookup() {
            OpenAIResponsesChatModel model =
                    OpenAIResponsesChatModel.builder()
                            .apiKey(API_KEY)
                            .modelName(MODEL_NAME)
                            .contextWindowSize(12345)
                            .build();
            assertEquals(12345, model.getContextWindowSize());
        }

        @Test
        void contextWindowSizeFallsBackToLookup() {
            OpenAIResponsesChatModel model =
                    OpenAIResponsesChatModel.builder()
                            .apiKey(API_KEY)
                            .modelName(MODEL_NAME)
                            .build();
            int expected =
                    io.agentscope.core.model.ModelContextWindows.lookup(
                            MODEL_NAME, io.agentscope.core.model.ModelContextWindows.OPENAI);
            assertEquals(expected, model.getContextWindowSize());
        }
    }

    // ── Builder boundary ─────────────────────────────────────────────

    @Nested
    class BuilderBoundary {

        @Test
        void builderDoesNotExposeClientMethod() {
            boolean found = false;
            for (java.lang.reflect.Method m :
                    OpenAIResponsesChatModel.Builder.class.getDeclaredMethods()) {
                if (m.getName().equals("client")) {
                    found = true;
                    break;
                }
            }
            assertFalse(found, "Builder should not expose client() method");
        }

        @Test
        void builderDoesNotExposeProxyMethod() {
            boolean found = false;
            for (java.lang.reflect.Method m :
                    OpenAIResponsesChatModel.Builder.class.getDeclaredMethods()) {
                if (m.getName().equals("proxy")) {
                    found = true;
                    break;
                }
            }
            assertFalse(found, "Builder should not expose proxy() method");
        }

        @Test
        void builderExposesFormatterMethod() {
            boolean found = false;
            for (java.lang.reflect.Method m :
                    OpenAIResponsesChatModel.Builder.class.getDeclaredMethods()) {
                if (m.getName().equals("formatter")) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Builder should expose formatter() method");
        }

        @Test
        void builderDoesNotExposeEndpointPathMethod() {
            boolean found = false;
            for (java.lang.reflect.Method m :
                    OpenAIResponsesChatModel.Builder.class.getDeclaredMethods()) {
                if (m.getName().equals("endpointPath")) {
                    found = true;
                    break;
                }
            }
            assertFalse(found, "Builder should not expose endpointPath() method");
        }

        @Test
        void builderDoesNotExposeHttpTransportMethod() {
            boolean found = false;
            for (java.lang.reflect.Method m :
                    OpenAIResponsesChatModel.Builder.class.getDeclaredMethods()) {
                if (m.getName().equals("httpTransport")) {
                    found = true;
                    break;
                }
            }
            assertFalse(found, "Builder should not expose httpTransport() method");
        }
    }

    // ── Retry and cancel ────────────────────────────────────────────

    @Nested
    class RetryAndCancel {

        @Test
        void streamCancelClosesSdkStream() throws Exception {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();

            CountDownLatch eventLatch = new CountDownLatch(1);
            CountDownLatch closeLatch = new CountDownLatch(1);

            StreamResponse<ResponseStreamEvent> streamResponse =
                    new StreamResponse<ResponseStreamEvent>() {
                        @Override
                        public Stream<ResponseStreamEvent> stream() {
                            return Stream.generate(
                                    () -> {
                                        eventLatch.countDown();
                                        try {
                                            Thread.sleep(100);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return TestSdkFixtures.textDeltaEvent("x", "msg_1");
                                    });
                        }

                        @Override
                        public void close() {
                            closeLatch.countDown();
                        }
                    };
            when(svc.createStreaming(any(ResponseCreateParams.class))).thenReturn(streamResponse);

            OpenAIResponsesChatModel model = createModel(client, true);
            Disposable disposable = model.stream(simpleMessages(), null, null).subscribe();
            assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "Stream should emit events");
            disposable.dispose();
            assertTrue(
                    closeLatch.await(2, TimeUnit.SECONDS),
                    "StreamResponse should be closed on cancel");
        }

        @Test
        void retryableErrorRetriedViaRetryWhen() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenThrow(TestSdkFixtures.rateLimit("rate limited"))
                    .thenReturn(TestSdkFixtures.textResponse("success"));

            OpenAIResponsesChatModel model = createModel(client, false);
            List<ChatResponse> results =
                    model.stream(simpleMessages(), null, null).collectList().block();

            assertNotNull(results);
            verify(svc, times(2)).create(any(ResponseCreateParams.class));
        }

        @Test
        void nonRetryableErrorNotRetried() {
            OpenAIClient client = mockClientWithResponseService();
            ResponseService svc = client.responses();
            when(svc.create(any(ResponseCreateParams.class)))
                    .thenThrow(TestSdkFixtures.badRequest("bad request"));

            OpenAIResponsesChatModel model = createModel(client, false);
            assertThrows(
                    OpenAIOfficialModelException.class,
                    () -> model.stream(simpleMessages(), null, null).collectList().block());
            verify(svc, times(1)).create(any(ResponseCreateParams.class));
        }
    }
}

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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link FallbackChainModel}: ordered fallback switching, failure classification
 * (switchable vs request-side), per-candidate cooldown with lazy recovery, and capability
 * delegation to the active candidate.
 */
@Tag("unit")
@DisplayName("FallbackChainModel Unit Tests")
class FallbackChainModelTest {

    @Test
    @DisplayName("Should succeed on the primary model without touching fallbacks")
    void primarySucceedsFallbacksUntouched() {
        CallRecordingModel primary = new CallRecordingModel("primary", null);
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, primary.callCount.get(), "primary should be called once");
        assertEquals(0, fallback.callCount.get(), "fallback should not be called");
    }

    @Test
    @DisplayName("Should switch to the first fallback on a switchable error")
    void switchesToFallbackOnSwitchableError() {
        CallRecordingModel primary =
                new CallRecordingModel(
                        "primary", new HttpTransportException("upstream 503", 503, ""));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, primary.callCount.get(), "primary should be attempted once");
        assertEquals(1, fallback.callCount.get(), "fallback should serve the request");
        assertEquals("fallback", chain.getModelName(), "active model should be the fallback");
    }

    @Test
    @DisplayName(
            "Should walk the whole chain and propagate the last error when all candidates fail")
    void allCandidatesFailPropagatesError() {
        HttpTransportException lastError = new HttpTransportException("all down", 503, "");
        CallRecordingModel primary =
                new CallRecordingModel(
                        "primary", new HttpTransportException("first down", 500, ""));
        CallRecordingModel fallback1 =
                new CallRecordingModel(
                        "fallback1", new HttpTransportException("second down", 502, ""));
        CallRecordingModel fallback2 = new CallRecordingModel("fallback2", lastError);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback1, fallback2));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectErrorSatisfies(
                        error ->
                                assertEquals(
                                        lastError.getStatusCode(),
                                        ((HttpTransportException) error).getStatusCode()))
                .verify();

        assertEquals(1, primary.callCount.get(), "primary should be attempted once");
        assertEquals(1, fallback1.callCount.get(), "fallback1 should be attempted once");
        assertEquals(1, fallback2.callCount.get(), "fallback2 should be attempted once");
    }

    @Test
    @DisplayName("Auth errors (401/403) should switch to the next candidate")
    void authErrorSwitchesToNextCandidate() {
        CallRecordingModel primary =
                new CallRecordingModel(
                        "primary", new HttpTransportException("invalid key", 401, ""));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, fallback.callCount.get(), "auth failure should fall back");
    }

    @Test
    @DisplayName("Timeout and network errors should switch to the next candidate")
    void timeoutAndNetworkErrorsSwitch() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new TimeoutException("read timeout"));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, fallback.callCount.get(), "timeout should fall back");
    }

    @Test
    @DisplayName("Request-side errors (400/422) should fail fast without switching")
    void requestSideErrorFailsFast() {
        HttpTransportException badRequest = new HttpTransportException("bad params", 400, "");
        CallRecordingModel primary = new CallRecordingModel("primary", badRequest);
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectErrorSatisfies(
                        error ->
                                assertEquals(
                                        Integer.valueOf(400),
                                        ((HttpTransportException) error).getStatusCode()))
                .verify();

        assertEquals(1, primary.callCount.get(), "primary should be attempted once");
        assertEquals(
                0, fallback.callCount.get(), "request-side failure must not consume fallbacks");
    }

    @Test
    @DisplayName("Mid-stream failures should not switch but still apply cooldown")
    void midStreamFailureDoesNotSwitch() {
        // Emits one chunk then fails.
        CallRecordingModel primary =
                new CallRecordingModel("primary", null) {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<io.agentscope.core.message.Msg> messages,
                            List<ToolSchema> tools,
                            GenerateOptions options) {
                        return Flux.concat(
                                Flux.just(textResponse("partial")),
                                Flux.error(new RuntimeException("mid-stream crash")));
                    }
                };
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .expectError()
                .verify();

        assertEquals(0, fallback.callCount.get(), "mid-stream failure must not switch");
    }

    @Test
    @DisplayName("Cooling candidate is skipped until its cooldown expires (lazy recovery)")
    void coolingCandidateSkippedThenRecovers() throws InterruptedException {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new HttpTransportException("down", 503, ""));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        // 300ms cooldown: short enough for the test, long enough to observe the skip.
        FallbackChainModel chain =
                new FallbackChainModel(primary, List.of(fallback), Duration.ofMillis(300));

        // First call: primary fails -> switches to fallback, primary enters cooldown.
        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, primary.callCount.get());

        // Second call within cooldown: primary must be skipped, fallback serves again.
        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, primary.callCount.get(), "cooling primary must be skipped");
        assertEquals(2, fallback.callCount.get());

        // Wait past the cooldown: primary becomes eligible again (lazy recovery) and fails again,
        // falling back once more.
        Thread.sleep(400);
        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(
                2, primary.callCount.get(), "primary should be retried after cooldown expires");
        assertEquals(3, fallback.callCount.get());
    }

    @Test
    @DisplayName("Null and empty fallback lists are allowed")
    void nullAndEmptyFallbacksAllowed() {
        CallRecordingModel primary = new CallRecordingModel("primary", null);

        FallbackChainModel emptyChain = new FallbackChainModel(primary, List.of());
        StepVerifier.create(emptyChain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        FallbackChainModel nullChain = new FallbackChainModel(primary, null);
        StepVerifier.create(nullChain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Null primary is rejected")
    void nullPrimaryRejected() {
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);
        assertThrows(
                NullPointerException.class, () -> new FallbackChainModel(null, List.of(fallback)));
    }

    @Test
    @DisplayName("Shared cooldown table persists across wrapper instances")
    void sharedCooldownSurvivesNewWrappers() throws InterruptedException {
        // Mirrors the agent wiring: modelForCall() creates a fresh FallbackChainModel on every
        // call, but all wrappers share the agent-scoped cooldown table.
        ConcurrentHashMap<String, Long> shared = new ConcurrentHashMap<>();
        CallRecordingModel primary =
                new CallRecordingModel("primary", new HttpTransportException("down", 503, ""));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel first =
                new FallbackChainModel(primary, List.of(fallback), Duration.ofMillis(300), shared);
        StepVerifier.create(first.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, primary.callCount.get());

        // A second, independent wrapper over the same models shares the cooldown: primary is
        // still cooling from the first wrapper's failure, so it is skipped.
        FallbackChainModel second =
                new FallbackChainModel(primary, List.of(fallback), Duration.ofMillis(300), shared);
        StepVerifier.create(second.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, primary.callCount.get(), "cooldown must persist across wrapper instances");
        assertEquals(2, fallback.callCount.get());

        // After the window expires the primary becomes eligible again.
        Thread.sleep(400);
        FallbackChainModel third =
                new FallbackChainModel(primary, List.of(fallback), Duration.ofMillis(300), shared);
        StepVerifier.create(third.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(2, primary.callCount.get(), "primary should be retried after cooldown");
    }

    @Test
    @DisplayName("Capability delegation reports the active candidate")
    void capabilityDelegationFollowsActiveCandidate() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new HttpTransportException("down", 503, ""));
        CallRecordingModel fallback =
                new CallRecordingModel("fallback", null) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }

                    @Override
                    public int getContextWindowSize() {
                        return 8192;
                    }
                };

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));
        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals("fallback", chain.getModelName());
        assertEquals(true, chain.supportsNativeStructuredOutput());
        assertEquals(8192, chain.getContextWindowSize());
    }

    @Test
    @DisplayName("ModelHttpException without retryable status fails fast (422)")
    void modelHttpExceptionNonRetryableFailsFast() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new TestModelHttpException(422, "validation"));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectErrorSatisfies(
                        error ->
                                assertEquals(
                                        Integer.valueOf(422),
                                        ((ModelHttpException) error).getStatusCode()))
                .verify();

        assertEquals(0, fallback.callCount.get(), "422 must not consume the fallback chain");
    }

    @Test
    @DisplayName("ModelHttpException with retryable status switches (429)")
    void modelHttpExceptionRetryableSwitches() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new TestModelHttpException(429, "rate limited"));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(
                1,
                fallback.callCount.get(),
                "429 ModelHttpException should fall back to next candidate");
    }

    @Test
    @DisplayName("HttpTransportException without a status code switches (connection error)")
    void transportExceptionWithoutStatusSwitches() {
        CallRecordingModel primary =
                new CallRecordingModel(
                        "primary",
                        new HttpTransportException(
                                "connect failed", new java.io.IOException("reset")));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, fallback.callCount.get(), "status-less transport error should fall back");
    }

    @Test
    @DisplayName("ModelHttpException without a status code switches (provider-level)")
    void modelHttpExceptionWithoutStatusSwitches() {
        // A ModelHttpException whose getStatusCode() returns null (no HTTP status available).
        TestModelHttpException statusLess = new TestModelHttpException(null, "no status");
        CallRecordingModel primary =
                new CallRecordingModel("primary", new RuntimeException(statusLess));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(
                1, fallback.callCount.get(), "status-less ModelHttpException should fall back");
    }

    @Test
    @DisplayName("IO errors should switch to the next candidate")
    void ioErrorSwitchesToNextCandidate() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new java.io.IOException("connect reset"));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, fallback.callCount.get(), "IO error should fall back");
    }

    @Test
    @DisplayName("Unknown errors switch conservatively (same as legacy single-fallback)")
    void unknownErrorSwitchesConservatively() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new IllegalStateException("boom"));
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, fallback.callCount.get(), "unknown error should conservatively fall back");
    }

    @Test
    @DisplayName("Wrapped (cause-chain) HTTP errors are classified by their inner status")
    void wrappedHttpErrorClassifiedByCause() {
        HttpTransportException inner = new HttpTransportException("inner 503", 503, "");
        RuntimeException wrapper = new RuntimeException("wrapped transport failure", inner);
        CallRecordingModel primary = new CallRecordingModel("primary", wrapper);
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, fallback.callCount.get(), "cause-chain 503 should be switchable");
    }

    @Test
    @DisplayName("Wrapped request-side error fails fast without switching")
    void wrappedRequestSideErrorFailsFast() {
        HttpTransportException inner = new HttpTransportException("inner 400", 400, "");
        RuntimeException wrapper = new RuntimeException("wrapped bad request", inner);
        CallRecordingModel primary = new CallRecordingModel("primary", wrapper);
        CallRecordingModel fallback = new CallRecordingModel("fallback", null);

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));

        StepVerifier.create(chain.stream(List.of(), null, null)).expectError().verify();

        assertEquals(
                0, fallback.callCount.get(), "wrapped 400 must not consume the fallback chain");
    }

    @Test
    @DisplayName("Capability delegation reports native structured output with tools")
    void capabilityDelegationIncludesWithTools() {
        CallRecordingModel primary =
                new CallRecordingModel("primary", new HttpTransportException("down", 503, ""));
        CallRecordingModel fallback =
                new CallRecordingModel("fallback", null) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }

                    @Override
                    public boolean supportsNativeStructuredOutputWithTools() {
                        return true;
                    }
                };

        FallbackChainModel chain = new FallbackChainModel(primary, List.of(fallback));
        StepVerifier.create(chain.stream(List.of(), null, null))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(true, chain.supportsNativeStructuredOutputWithTools());
    }

    @Test
    @DisplayName("Null shared cooldown table is rejected")
    void nullSharedCooldownRejected() {
        CallRecordingModel primary = new CallRecordingModel("primary", null);
        assertThrows(
                NullPointerException.class,
                () -> new FallbackChainModel(primary, List.of(), Duration.ofSeconds(1), null));
    }

    /** ModelHttpException stub for classification tests (mirrors ExecutionConfigTest). */
    private static final class TestModelHttpException extends RuntimeException
            implements ModelHttpException {

        private final Integer statusCode;

        private TestModelHttpException(Integer statusCode, String message) {
            super("HTTP " + statusCode + ": " + message);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }

    /** Model that returns a canned success or error, and records how many times it was called. */
    private static class CallRecordingModel implements Model {

        private final String name;
        private final Throwable error;
        private final AtomicInteger callCount = new AtomicInteger();

        CallRecordingModel(String name, Throwable error) {
            this.name = name;
            this.error = error;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            callCount.incrementAndGet();
            if (error != null) {
                return Flux.error(error);
            }
            return Flux.just(textResponse(name));
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .id("msg-" + text)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}

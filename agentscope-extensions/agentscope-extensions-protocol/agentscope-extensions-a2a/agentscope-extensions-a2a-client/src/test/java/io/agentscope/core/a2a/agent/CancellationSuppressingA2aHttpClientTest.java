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

package io.agentscope.core.a2a.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CancellationSuppressingA2aHttpClientTest {

    private static final String FINAL_STATUS_UPDATE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"request-id\","
                    + "\"result\":{\"kind\":\"status-update\",\"final\":true}}";

    @Test
    void suppressesCancellationAfterFinalStatusUpdate() throws Exception {
        CallbackCapture callbacks = new CallbackCapture();
        A2AHttpClient client = newClient(callbacks);
        AtomicInteger errorCount = new AtomicInteger();

        client.createPost()
                .postAsyncSSE(message -> {}, error -> errorCount.incrementAndGet(), () -> {});

        callbacks.messageConsumer.get().accept(FINAL_STATUS_UPDATE);
        callbacks
                .errorConsumer
                .get()
                .accept(new CompletionException(new CancellationException("Request cancelled")));

        assertEquals(0, errorCount.get());
    }

    @Test
    void forwardsCancellationBeforeFinalStatusUpdate() throws Exception {
        CallbackCapture callbacks = new CallbackCapture();
        A2AHttpClient client = newClient(callbacks);
        AtomicInteger errorCount = new AtomicInteger();

        client.createPost()
                .postAsyncSSE(message -> {}, error -> errorCount.incrementAndGet(), () -> {});

        callbacks.errorConsumer.get().accept(new CancellationException("Request cancelled"));

        assertEquals(1, errorCount.get());
    }

    @Test
    void delegatesOtherHttpClientBuilders() {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.GetBuilder getBuilder = mock(A2AHttpClient.GetBuilder.class);
        A2AHttpClient.DeleteBuilder deleteBuilder = mock(A2AHttpClient.DeleteBuilder.class);
        when(delegate.createGet()).thenReturn(getBuilder);
        when(delegate.createDelete()).thenReturn(deleteBuilder);

        A2AHttpClient client = new CancellationSuppressingA2aHttpClient(delegate);

        assertSame(getBuilder, client.createGet());
        assertSame(deleteBuilder, client.createDelete());
        verify(delegate).createGet();
        verify(delegate).createDelete();
    }

    @Test
    void delegatesPostBuilderConfigurationAndSynchronousRequests() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.PostBuilder postBuilder = mock(A2AHttpClient.PostBuilder.class);
        A2AHttpResponse response = mock(A2AHttpResponse.class);
        when(delegate.createPost()).thenReturn(postBuilder);
        when(postBuilder.post()).thenReturn(response);

        A2AHttpClient.PostBuilder wrapped =
                new CancellationSuppressingA2aHttpClient(delegate).createPost();

        assertSame(wrapped, wrapped.url("https://example.test"));
        assertSame(wrapped, wrapped.addHeader("Authorization", "Bearer token"));
        assertSame(wrapped, wrapped.addHeaders(java.util.Map.of("Accept", "application/json")));
        assertSame(wrapped, wrapped.body("{}"));
        assertSame(response, wrapped.post());
        verify(postBuilder).url("https://example.test");
        verify(postBuilder).addHeader("Authorization", "Bearer token");
        verify(postBuilder).addHeaders(java.util.Map.of("Accept", "application/json"));
        verify(postBuilder).body("{}");
        verify(postBuilder).post();
    }

    @Test
    void forwardsCompletionCallback() throws Exception {
        CallbackCapture callbacks = new CallbackCapture();
        A2AHttpClient client = newClient(callbacks);
        AtomicInteger completionCount = new AtomicInteger();

        client.createPost()
                .postAsyncSSE(message -> {}, error -> {}, completionCount::incrementAndGet);

        callbacks.completeRunnable.get().run();

        assertEquals(1, completionCount.get());
    }

    @Test
    void forwardsCancellationForNonFinalStatusUpdate() throws Exception {
        CallbackCapture callbacks = new CallbackCapture();
        A2AHttpClient client = newClient(callbacks);
        AtomicInteger errorCount = new AtomicInteger();

        client.createPost()
                .postAsyncSSE(message -> {}, error -> errorCount.incrementAndGet(), () -> {});

        callbacks
                .messageConsumer
                .get()
                .accept(
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"kind\":\"status-update\",\"final\":false}}");
        callbacks.errorConsumer.get().accept(new CancellationException("Request cancelled"));

        assertEquals(1, errorCount.get());
    }

    @Test
    void ignoresMalformedAndNonResultMessagesWhenDetectingFinalStatus() throws Exception {
        CallbackCapture malformedCallbacks = new CallbackCapture();
        A2AHttpClient malformedClient = newClient(malformedCallbacks);
        AtomicInteger malformedErrorCount = new AtomicInteger();
        malformedClient
                .createPost()
                .postAsyncSSE(
                        message -> {}, error -> malformedErrorCount.incrementAndGet(), () -> {});

        malformedCallbacks.messageConsumer.get().accept("not-json");
        malformedCallbacks
                .errorConsumer
                .get()
                .accept(new CancellationException("Request cancelled"));

        CallbackCapture missingResultCallbacks = new CallbackCapture();
        A2AHttpClient missingResultClient = newClient(missingResultCallbacks);
        AtomicInteger missingResultErrorCount = new AtomicInteger();
        missingResultClient
                .createPost()
                .postAsyncSSE(
                        message -> {},
                        error -> missingResultErrorCount.incrementAndGet(),
                        () -> {});

        missingResultCallbacks.messageConsumer.get().accept("{\"jsonrpc\":\"2.0\"}");
        missingResultCallbacks
                .messageConsumer
                .get()
                .accept(
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"kind\":\"artifact-update\",\"final\":true}}");
        missingResultCallbacks
                .errorConsumer
                .get()
                .accept(new CancellationException("Request cancelled"));

        assertEquals(1, malformedErrorCount.get());
        assertEquals(1, missingResultErrorCount.get());
    }

    @Test
    void forwardsNonCancellationErrorsAfterFinalStatusUpdate() throws Exception {
        CallbackCapture callbacks = new CallbackCapture();
        A2AHttpClient client = newClient(callbacks);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        client.createPost().postAsyncSSE(message -> {}, receivedError::set, () -> {});

        callbacks.messageConsumer.get().accept(FINAL_STATUS_UPDATE);
        IOException expected = new IOException("transport failed");
        callbacks.errorConsumer.get().accept(expected);

        assertEquals(expected, receivedError.get());
    }

    private A2AHttpClient newClient(CallbackCapture callbacks) throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.PostBuilder postBuilder = mock(A2AHttpClient.PostBuilder.class);
        when(delegate.createPost()).thenReturn(postBuilder);
        doAnswer(
                        invocation -> {
                            callbacks.messageConsumer.set(invocation.getArgument(0));
                            callbacks.errorConsumer.set(invocation.getArgument(1));
                            callbacks.completeRunnable.set(invocation.getArgument(2));
                            return new CompletableFuture<>();
                        })
                .when(postBuilder)
                .postAsyncSSE(any(), any(), any());
        return new CancellationSuppressingA2aHttpClient(delegate);
    }

    private static final class CallbackCapture {
        private final AtomicReference<Consumer<String>> messageConsumer = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> errorConsumer = new AtomicReference<>();
        private final AtomicReference<Runnable> completeRunnable = new AtomicReference<>();
    }
}

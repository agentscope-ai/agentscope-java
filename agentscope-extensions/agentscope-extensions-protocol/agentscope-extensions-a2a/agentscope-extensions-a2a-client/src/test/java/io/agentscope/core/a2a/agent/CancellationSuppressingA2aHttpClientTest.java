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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.client.http.A2AHttpClient;
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
                            return new CompletableFuture<>();
                        })
                .when(postBuilder)
                .postAsyncSSE(any(), any(), any());
        return new CancellationSuppressingA2aHttpClient(delegate);
    }

    private static final class CallbackCapture {
        private final AtomicReference<Consumer<String>> messageConsumer = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> errorConsumer = new AtomicReference<>();
    }
}

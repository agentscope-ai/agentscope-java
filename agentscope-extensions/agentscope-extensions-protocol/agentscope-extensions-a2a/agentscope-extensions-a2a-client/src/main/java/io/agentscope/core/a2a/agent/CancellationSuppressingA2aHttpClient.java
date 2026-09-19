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

import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.util.Utils;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Applies the A2A Java SDK 1.1 SSE cancellation fix to the v0.3 client API.
 *
 * <p>The v0.3.3 SDK cancels its HTTP future after delivering a final status update. The JDK HTTP
 * client can report that internal cleanup as a {@link CancellationException}, which is then passed
 * to the streaming error callback. This decorator keeps the v0.3 SDK/API and suppresses only that
 * cancellation after a final status-update event has been received.
 *
 * <p>This class is intentionally package-private. It is an implementation detail of the default
 * JSON-RPC transport and can be removed when AgentScope moves to a v0.3-compatible SDK release
 * containing the upstream fix.
 */
final class CancellationSuppressingA2aHttpClient implements A2AHttpClient {

    private final A2AHttpClient delegate;

    CancellationSuppressingA2aHttpClient() {
        this(new JdkA2AHttpClient());
    }

    CancellationSuppressingA2aHttpClient(A2AHttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public GetBuilder createGet() {
        return delegate.createGet();
    }

    @Override
    public PostBuilder createPost() {
        return new CancellationSuppressingPostBuilder(delegate.createPost());
    }

    @Override
    public DeleteBuilder createDelete() {
        return delegate.createDelete();
    }

    private static boolean isCancellation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFinalStatusUpdate(String message) {
        try {
            JsonNode root = Utils.OBJECT_MAPPER.readTree(message);
            JsonNode result = root.get("result");
            return result != null
                    && "status-update".equals(result.path("kind").asText())
                    && result.path("final").asBoolean(false);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static final class CancellationSuppressingPostBuilder implements PostBuilder {

        private final PostBuilder delegate;

        private final AtomicBoolean completed = new AtomicBoolean();

        private CancellationSuppressingPostBuilder(PostBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public PostBuilder url(String url) {
            delegate.url(url);
            return this;
        }

        @Override
        public PostBuilder addHeaders(Map<String, String> headers) {
            delegate.addHeaders(headers);
            return this;
        }

        @Override
        public PostBuilder addHeader(String name, String value) {
            delegate.addHeader(name, value);
            return this;
        }

        @Override
        public PostBuilder body(String body) {
            delegate.body(body);
            return this;
        }

        @Override
        public A2AHttpResponse post() throws IOException, InterruptedException {
            return delegate.post();
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(
                Consumer<String> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable)
                throws IOException, InterruptedException {
            completed.set(false);
            return delegate.postAsyncSSE(
                    message -> {
                        if (isFinalStatusUpdate(message)) {
                            completed.set(true);
                        }
                        messageConsumer.accept(message);
                    },
                    error -> {
                        if (!completed.get() || !isCancellation(error)) {
                            errorConsumer.accept(error);
                        }
                    },
                    completeRunnable);
        }
    }
}

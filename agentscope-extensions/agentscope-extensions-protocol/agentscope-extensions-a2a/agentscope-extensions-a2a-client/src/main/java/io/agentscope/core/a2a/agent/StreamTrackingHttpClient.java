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

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

/**
 * An {@link A2AHttpClient} that keeps the futures of the server-sent event streams it opens, so
 * {@link #cancelStreams()} can close them.
 *
 * <p>The JSON-RPC transport keeps the future of a streaming request to itself and its {@code
 * close()} does nothing, so without this a cancelled call leaves its stream open: the events keep
 * arriving until the remote agent ends the task.
 */
final class StreamTrackingHttpClient implements A2AHttpClient {

    private final A2AHttpClient delegate;

    private final Queue<CompletableFuture<Void>> streams = new ConcurrentLinkedQueue<>();

    StreamTrackingHttpClient(A2AHttpClient delegate) {
        this.delegate = delegate;
    }

    /** Cancels the streams this client opened; it does nothing for those that already ended. */
    void cancelStreams() {
        CompletableFuture<Void> stream;
        while ((stream = streams.poll()) != null) {
            stream.cancel(true);
        }
    }

    private CompletableFuture<Void> track(CompletableFuture<Void> stream) {
        streams.add(stream);
        stream.whenComplete((ignored, error) -> streams.remove(stream));
        return stream;
    }

    @Override
    public GetBuilder createGet() {
        return new TrackingGetBuilder(delegate.createGet());
    }

    @Override
    public PostBuilder createPost() {
        return new TrackingPostBuilder(delegate.createPost());
    }

    @Override
    public DeleteBuilder createDelete() {
        return delegate.createDelete();
    }

    private final class TrackingGetBuilder implements GetBuilder {

        private final GetBuilder delegate;

        private TrackingGetBuilder(GetBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public GetBuilder url(String s) {
            delegate.url(s);
            return this;
        }

        @Override
        public GetBuilder addHeaders(Map<String, String> headers) {
            delegate.addHeaders(headers);
            return this;
        }

        @Override
        public GetBuilder addHeader(String name, String value) {
            delegate.addHeader(name, value);
            return this;
        }

        @Override
        public GetBuilder followRedirects(boolean follow) {
            delegate.followRedirects(follow);
            return this;
        }

        @Override
        public A2AHttpResponse get() throws IOException, InterruptedException {
            return delegate.get();
        }

        @Override
        public CompletableFuture<Void> getAsyncSSE(
                Consumer<ServerSentEvent> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable)
                throws IOException, InterruptedException {
            return track(delegate.getAsyncSSE(messageConsumer, errorConsumer, completeRunnable));
        }
    }

    private final class TrackingPostBuilder implements PostBuilder {

        private final PostBuilder delegate;

        private TrackingPostBuilder(PostBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public PostBuilder url(String s) {
            delegate.url(s);
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
        public PostBuilder followRedirects(boolean follow) {
            delegate.followRedirects(follow);
            return this;
        }

        @Override
        public A2AHttpResponse post() throws IOException, InterruptedException {
            return delegate.post();
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(
                Consumer<ServerSentEvent> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable)
                throws IOException, InterruptedException {
            return track(delegate.postAsyncSSE(messageConsumer, errorConsumer, completeRunnable));
        }
    }
}

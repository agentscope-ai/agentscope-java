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
package io.agentcore.managed.harness.runtime;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounded synchronous client; invoke only on a worker, never on a reactive event-loop. */
public final class SessionServiceClient {
    private final URI base;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration timeout;

    public SessionServiceClient(URI base, String token, ObjectMapper mapper, Duration timeout) {
        if (base.getHost() == null
                || !java.util.Set.of("http", "https").contains(base.getScheme())
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || token == null
                || token.isBlank()
                || timeout.isNegative()
                || timeout.isZero())
            throw new IllegalArgumentException(
                    "Session Service URL, credential and timeout are required");
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.token = token;
        this.mapper = mapper.copy();
        this.mapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(90 * 1024 * 1024).build());
        this.timeout = timeout;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public JsonNode post(String operation, Object body) {
        if (!operation.matches("[a-z][a-z/-]*"))
            throw new IllegalArgumentException("Invalid operation");
        try {
            var request =
                    HttpRequest.newBuilder(base.resolve(operation))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            mapper.writeValueAsBytes(body)))
                            .build();
            var response = http.send(request, ignored -> new BoundedBody());
            // A proxy/server failure is not evidence that the transaction rolled back.
            // Commit callers must query the immutable receipt before attempting another write.
            if (response.statusCode() >= 500
                    || response.statusCode() == 408
                    || response.statusCode() == 429)
                throw new Unavailable(
                        "Session Service response was not confirmed (HTTP "
                                + response.statusCode()
                                + ")",
                        null);
            var value = mapper.readTree(response.body());
            if (value == null) throw new IOException("Missing Session Service response");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new Rejected(
                        response.statusCode(), value.path("code").asText("service_error"));
            return value;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Session Service request interrupted", interrupted);
        } catch (IOException failure) {
            throw new Unavailable("Session Service response was not confirmed", failure);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean failed;

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) size += buffer.remaining();
            if (size > 90L * 1024 * 1024) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Response exceeds limit"));
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable failure) {
            if (!failed) {
                failed = true;
                delegate.onError(failure);
            }
        }

        public void onComplete() {
            if (!failed) delegate.onComplete();
        }
    }

    public static final class Rejected extends RuntimeException {
        private final int status;
        private final String code;

        public Rejected(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

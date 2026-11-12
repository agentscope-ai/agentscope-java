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

package io.agentscope.extensions.durable.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableProtocol.Endpoint;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.ObjectStore.Reference;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Versioned transport only; all transaction semantics remain in SessionBackend/ExecutionStore. */
public final class DurableHttp implements Function<DurableProtocol.Request, JsonNode> {
    public static final String VERSION = DurableProtocol.VERSION;
    public static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;
    private final Consumer<HttpRequest.Builder> headers;

    /** Endpoint is the exact route URI, for example https://host/v1/backend. */
    public DurableHttp(
            URI endpoint,
            HttpClient client,
            Duration timeout,
            Consumer<HttpRequest.Builder> headers) {
        this.endpoint = endpoint;
        this.client = client;
        this.timeout = timeout;
        this.headers = headers;
    }

    public DurableHttp(URI endpoint) {
        this(
                endpoint,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(30),
                request -> {});
    }

    /** Public operation transport, configured only on DurableRuntime.builder().transport(...). */
    @Override
    public JsonNode apply(DurableProtocol.Request request) {
        DurableProtocol.validateRequest(Endpoint.USER, DurableJson.value(request));
        return call(Endpoint.USER, request.operation(), request.payload());
    }

    public JsonNode call(Endpoint endpointType, String operation, Object payload) {
        var envelope = DurableProtocol.request(endpointType, operation, payload);
        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) {
            if (endpointType != Endpoint.BACKEND)
                throw new DurableException(
                        DurableException.Code.INVALID, "Oversized public/executor HTTP request");
            var key = session(envelope.path("payload"));
            if (key == null)
                throw new DurableException(
                        DurableException.Code.INVALID, "Large request requires a session");
            var reference = putObject(key, bytes);
            envelope =
                    DurableProtocol.request(
                            endpointType,
                            "invokeObject",
                            Map.of("session", key, "operation", operation, "reference", reference));
        }
        var builder =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(envelope.toString()));
        headers.accept(builder);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body;
            try {
                body = DurableJson.parse(response.body());
            } catch (RuntimeException malformed) {
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE,
                        "Remote Durable response is malformed; outcome is unconfirmed",
                        malformed);
            }
            if (!VERSION.equals(body.path("version").asText()))
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE, "Unknown Durable HTTP protocol");
            if (response.statusCode() != 200) {
                try {
                    DurableProtocol.validate("error", body);
                } catch (RuntimeException malformed) {
                    throw new DurableException(
                            DurableException.Code.UNAVAILABLE,
                            "Remote Durable error response is invalid",
                            malformed);
                }
                DurableException.Code code;
                try {
                    code = DurableException.Code.valueOf(body.path("code").asText());
                } catch (IllegalArgumentException error) {
                    code = DurableException.Code.UNAVAILABLE;
                }
                throw new DurableException(
                        code, body.path("message").asText("Remote request failed"));
            }
            return DurableProtocol.response(endpointType, operation, body);
        } catch (IOException error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Durable HTTP outcome is unconfirmed",
                    error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Durable HTTP interrupted", error);
        }
    }

    public Reference putObject(SessionKey session, byte[] bytes) {
        if (bytes.length > ObjectStore.MAX_BYTES)
            throw new IllegalArgumentException("Object exceeds size limit");
        byte[] response = objectRequest("PUT", Map.of("session", session), bytes);
        var envelope = DurableJson.parse(new String(response, StandardCharsets.UTF_8));
        return DurableJson.convert(
                DurableProtocol.response(Endpoint.BACKEND, "putObject", envelope), Reference.class);
    }

    public byte[] readObject(SessionKey session, Reference reference) {
        return ObjectStore.verify(
                reference.store(),
                reference,
                objectRequest("GET", Map.of("session", session, "reference", reference), null));
    }

    private byte[] objectRequest(String method, Object metadata, byte[] bytes) {
        var builder =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/octet-stream")
                        .header("X-Durable-Version", VERSION)
                        .header(
                                "X-Durable-Object",
                                Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(
                                                DurableJson.value(metadata)
                                                        .toString()
                                                        .getBytes(StandardCharsets.UTF_8)))
                        .method(
                                method,
                                bytes == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.accept(builder);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] content = stream.readNBytes(ObjectStore.MAX_BYTES + 1);
                if (content.length > ObjectStore.MAX_BYTES)
                    throw new DurableException(
                            DurableException.Code.INVALID, "Object response exceeds limit");
                if (response.statusCode() == 200) return content;
                var error = DurableJson.parse(new String(content, StandardCharsets.UTF_8));
                DurableProtocol.validate("error", error);
                throw new DurableException(
                        DurableException.Code.valueOf(error.path("code").asText()),
                        error.path("message").asText());
            }
        } catch (IOException error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Object transfer unconfirmed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Object transfer interrupted", error);
        }
    }

    static SessionKey session(JsonNode payload) {
        JsonNode key;
        if (payload.has("definition")) {
            key = payload.path("definition").path("key");
            if (key.has("session")) key = key.get("session");
        } else if (payload.has("lease")) key = payload.path("lease").path("thread").path("session");
        else if (payload.has("thread")) key = payload.path("thread").path("session");
        else key = payload.get("session");
        return key == null || key.isNull() || key.isMissingNode()
                ? null
                : DurableJson.convert(key, SessionKey.class);
    }

    static JsonNode request(HttpExchange exchange, Endpoint endpoint) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod()))
            throw new DurableException(DurableException.Code.INVALID, "POST required");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES)
            throw new DurableException(DurableException.Code.INVALID, "Request exceeds size limit");
        var body = DurableJson.parse(new String(bytes, StandardCharsets.UTF_8));
        DurableProtocol.validateRequest(endpoint, body);
        return body;
    }

    static void result(HttpExchange exchange, Object result) throws IOException {
        respond(exchange, 200, Map.of("version", VERSION, "result", DurableJson.value(result)));
    }

    static void error(HttpExchange exchange, RuntimeException error) throws IOException {
        var code =
                error instanceof DurableException durable
                        ? durable.code()
                        : error instanceof IllegalArgumentException
                                        || error instanceof SecurityException
                                ? DurableException.Code.INVALID
                                : DurableException.Code.UNAVAILABLE;
        int status =
                error instanceof SecurityException
                        ? 403
                        : switch (code) {
                            case NOT_FOUND -> 404;
                            case CONFLICT, FENCED -> 409;
                            case CURSOR_EXPIRED -> 410;
                            case UNAVAILABLE -> 503;
                            default -> 400;
                        };
        String message =
                error instanceof DurableException
                        ? error.getMessage()
                        : error instanceof SecurityException
                                ? "Access denied"
                                : code == DurableException.Code.UNAVAILABLE
                                        ? "Durable operation outcome is unconfirmed"
                                        : "Invalid request";
        respond(
                exchange,
                status,
                Map.of("version", VERSION, "code", code.name(), "message", message));
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = DurableJson.value(body).toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

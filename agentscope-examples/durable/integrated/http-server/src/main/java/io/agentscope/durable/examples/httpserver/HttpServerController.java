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
package io.agentscope.durable.examples.httpserver;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionEventStream;
import io.agentscope.extensions.durable.http.DurableHttp;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC endpoints for the public Runtime protocol and its replayable event stream. */
@RestController
final class HttpServerController {
    private static final Set<String> EVENT_PARAMETERS =
            Set.of("namespace", "sessionId", "cursor", "threadId", "turnId", "runId");

    private final DurableRuntime runtime;
    private final HttpServerProperties properties;
    private final AsyncTaskExecutor taskExecutor;

    HttpServerController(
            DurableRuntime runtime,
            HttpServerProperties properties,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.runtime = runtime;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping(
            path = "/api",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> invoke(@RequestBody byte[] bytes) {
        if (bytes.length > DurableHttp.MAX_REQUEST_BYTES)
            throw new DurableException(DurableException.Code.INVALID, "Request exceeds size limit");
        var envelope = DurableJson.parse(new String(bytes, StandardCharsets.UTF_8));
        DurableProtocol.validateRequest(DurableProtocol.Endpoint.USER, envelope);
        var request = DurableJson.convert(envelope, DurableProtocol.Request.class);
        authorize(request);
        return json(
                HttpStatus.OK,
                Map.of("version", DurableHttp.VERSION, "result", runtime.invoke(request)));
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        var query = validate(parameters);
        var session = new SessionKey(query.get("namespace"), query.get("sessionId"));
        authorize(session.namespace());
        var handle = runtime.getSession(session);
        var filter =
                new EventFilter(query.get("threadId"), query.get("turnId"), query.get("runId"));
        String cursor =
                lastEventId == null || lastEventId.isBlank() ? query.get("cursor") : lastEventId;
        var stream =
                handle.streamEvents(
                        filter,
                        EventStreamOptions.builder()
                                .after(cursor)
                                .deltas(EventDeltaType.values())
                                .build());
        var emitter = new SseEmitter(Duration.ofSeconds(30).toMillis());
        emitter.onCompletion(stream::close);
        emitter.onTimeout(
                () -> {
                    stream.close();
                    emitter.complete();
                });
        emitter.onError(error -> stream.close());
        try {
            taskExecutor.execute(() -> transfer(stream, new SpringSseWriter(emitter)));
        } catch (RuntimeException error) {
            stream.close();
            throw error;
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<byte[]> error(RuntimeException error) {
        var code =
                error instanceof DurableException durable
                        ? durable.code()
                        : error instanceof IllegalArgumentException
                                        || error instanceof SecurityException
                                ? DurableException.Code.INVALID
                                : DurableException.Code.UNAVAILABLE;
        var status =
                error instanceof SecurityException
                        ? HttpStatus.FORBIDDEN
                        : switch (code) {
                            case NOT_FOUND -> HttpStatus.NOT_FOUND;
                            case CONFLICT, FENCED -> HttpStatus.CONFLICT;
                            case CURSOR_EXPIRED -> HttpStatus.GONE;
                            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                            default -> HttpStatus.BAD_REQUEST;
                        };
        String message =
                error instanceof DurableException
                        ? error.getMessage()
                        : error instanceof SecurityException
                                ? "Access denied"
                                : code == DurableException.Code.UNAVAILABLE
                                        ? "Durable operation outcome is unconfirmed"
                                        : "Invalid request";
        return json(
                status,
                Map.of("version", DurableHttp.VERSION, "code", code.name(), "message", message));
    }

    private void transfer(SessionEventStream stream, SpringSseWriter writer) {
        try (stream) {
            stream.transferTo(writer, Duration.ofSeconds(30));
        } catch (UncheckedIOException disconnected) {
            // The client reconnects with the last committed event ID.
        } catch (IllegalStateException disconnected) {
            // SseEmitter was already completed by the container or remote client.
        } finally {
            writer.complete();
        }
    }

    private void authorize(DurableProtocol.Request request) {
        var payload = request.payload();
        SessionKey session =
                payload.has("session")
                        ? DurableJson.convert(payload.get("session"), SessionKey.class)
                        : null;
        String namespace =
                session == null
                        ? payload.has("namespace") ? payload.path("namespace").asText() : null
                        : session.namespace();
        authorize(namespace);
        if ("fork".equals(request.operation()))
            authorize(
                    DurableJson.convert(payload.path("target").path("session"), SessionKey.class)
                            .namespace());
    }

    private void authorize(String namespace) {
        if (namespace != null && !properties.getNamespace().equals(namespace))
            throw new SecurityException("Unknown namespace");
    }

    private static Map<String, String> validate(MultiValueMap<String, String> parameters) {
        for (var entry : parameters.entrySet())
            if (!EVENT_PARAMETERS.contains(entry.getKey()) || entry.getValue().size() != 1)
                throw new DurableException(DurableException.Code.INVALID, "Invalid SSE query");
        return parameters.toSingleValueMap();
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .contentType(MediaType.APPLICATION_JSON)
                .body(DurableJson.value(body).toString().getBytes(StandardCharsets.UTF_8));
    }
}

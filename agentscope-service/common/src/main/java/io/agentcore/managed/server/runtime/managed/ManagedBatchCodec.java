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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.io.IOException;
import java.nio.file.Path;

/** Immutable private shared blobs keep large checkpoints below the Broker payload ceiling. */
public final class ManagedBatchCodec {
    private static final int INLINE_LIMIT = 128 * 1024;
    private static final int BLOB_LIMIT = 64 * 1024 * 1024;

    public record Envelope(
            int version,
            String kind,
            ManagedScope scope,
            String batchId,
            String hash,
            byte[] inline,
            String backendId) {}

    private final ObjectMapper mapper;
    private final RuntimeObjectStore objects;

    /** Root must be a private shared volume accessible by HarnessRuntime and Tool Worker, not a public artifact path. */
    public ManagedBatchCodec(ObjectMapper mapper, Path root) {
        this(mapper, new FileRuntimeObjectStore(root));
    }

    public ManagedBatchCodec(ObjectMapper mapper, RuntimeObjectStore objects) {
        this.mapper = mapper;
        this.objects = objects;
    }

    public Message encode(ManagedToolResult result) {
        return encode("tool_result", result.scope(), result.batchId(), result);
    }

    public Message encode(ManagedToolEvent event) {
        var message = encode("tool_event", event.scope(), event.eventId(), event);
        return new Message(
                message.id(),
                ManagedScope.fingerprint(event.scope().messageGroup(), event.taskId())
                        .substring(0, 48),
                message.body());
    }

    public Message encode(ManagedToolCommand command) {
        var message = encode("tool_command", command.scope(), command.commandId(), command);
        return new Message(message.id(), command.messageGroup(), message.body());
    }

    public ManagedToolCommand decodeToolCommand(Message message) {
        var envelope = envelope(message);
        if (!"tool_command".equals(envelope.kind()))
            throw new IllegalArgumentException("Not a tool command");
        var command = parse(content(envelope), ManagedToolCommand.class);
        if (!command.scope().equals(envelope.scope())
                || !command.commandId().equals(message.id())
                || !command.messageGroup().equals(message.messageGroup()))
            throw new IllegalArgumentException("Tool command identity differs");
        return command;
    }

    public ManagedToolEvent decodeToolEvent(Message message) {
        var envelope = envelope(message);
        if (!"tool_event".equals(envelope.kind()))
            throw new IllegalArgumentException("Not a tool source event");
        var event = parse(content(envelope), ManagedToolEvent.class);
        if (!event.scope().equals(envelope.scope())
                || !event.eventId().equals(envelope.batchId())
                || !message.messageGroup()
                        .equals(
                                ManagedScope.fingerprint(
                                                event.scope().messageGroup(), event.taskId())
                                        .substring(0, 48)))
            throw new IllegalArgumentException("Tool source identity differs");
        return event;
    }

    public String kind(Message message) {
        return envelope(message).kind();
    }

    private Message encode(String kind, ManagedScope scope, String id, Object value) {
        byte[] content = bytes(value);
        if (content.length > BLOB_LIMIT)
            throw new IllegalArgumentException("Runtime batch exceeds blob limit");
        String hash = digest(content);
        if (content.length > INLINE_LIMIT) store(scope, hash, content);
        var envelope =
                new Envelope(
                        2,
                        kind,
                        scope,
                        id,
                        hash,
                        content.length <= INLINE_LIMIT ? content : null,
                        objects.backendId());
        return new Message(id, scope.messageGroup(), bytes(envelope));
    }

    public ManagedToolResult decodeToolResult(Message message) {
        var envelope = envelope(message);
        if (!"tool_result".equals(envelope.kind()))
            throw new IllegalArgumentException("Not a tool result");
        var result = parse(content(envelope), ManagedToolResult.class);
        if (!result.scope().equals(envelope.scope())
                || !result.batchId().equals(envelope.batchId()))
            throw new IllegalArgumentException("Tool result identity differs");
        return result;
    }

    private Envelope envelope(Message message) {
        var envelope = parse(message.body(), Envelope.class);
        if (!objects.backendId().equals(envelope.backendId())
                || envelope.version() != 2
                || envelope.scope() == null
                || !message.id().equals(envelope.batchId())
                || (!message.messageGroup().equals(envelope.scope().messageGroup())
                        && !"tool_event".equals(envelope.kind())
                        && !"tool_command".equals(envelope.kind())))
            throw new IllegalArgumentException("Runtime envelope identity differs");
        return envelope;
    }

    private byte[] content(Envelope envelope) {
        byte[] content =
                envelope.inline() == null
                        ? load(envelope.scope(), envelope.hash())
                        : envelope.inline();
        if (content.length > BLOB_LIMIT || !digest(content).equals(envelope.hash()))
            throw new IllegalArgumentException("Runtime blob content hash differs");
        return content;
    }

    private void store(ManagedScope scope, String hash, byte[] content) {
        objects.put(scope.messageGroup(), "batch", hash, content);
    }

    private byte[] load(ManagedScope scope, String hash) {
        byte[] content = objects.read(scope.messageGroup(), "batch", hash);
        if (content.length > BLOB_LIMIT || !digest(content).equals(hash))
            throw new ManagedTransportException("Runtime blob hash differs");
        return content;
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode runtime batch", error);
        }
    }

    private <T> T parse(byte[] value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot decode runtime batch", error);
        }
    }

    private static String digest(byte[] bytes) {
        return RuntimeObjectStore.sha256(bytes);
    }
}

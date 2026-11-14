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

package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.ThreadView;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared resource/message/tool assembly for snapshots and ordered replay. Turns are queried separately. */
public final class EventProjection {
    private final SessionKey session;
    private final EventFilter filter;
    private ObjectNode value = empty();
    private BigInteger sequence = BigInteger.ZERO;
    private String cursor;

    public EventProjection(SessionKey session) {
        this(session, EventFilter.all());
    }

    public EventProjection(SessionKey session, EventFilter filter) {
        this.session = Objects.requireNonNull(session);
        this.filter = Objects.requireNonNull(filter);
    }

    public synchronized void reset(Snapshot snapshot) {
        DurableProtocol.validate("snapshot", snapshot);
        requireSession(snapshot.session());
        requireResources(snapshot.projection());
        value = (ObjectNode) filter.select(snapshot).projection().deepCopy();
        sequence = new BigInteger(snapshot.sequence());
        cursor = snapshot.cursor();
    }

    /**
     * Replayed events are ignored. Unfiltered streams require consecutive sequences; filtered
     * streams are sparse and rely on the server cursor for completeness. Use the same filter for
     * the subscription and this projection, including snapshot resynchronization.
     */
    public synchronized void accept(PublicEvent event) {
        requireSession(event.session());
        if (!"1".equals(event.schemaVersion()))
            throw new DurableException(DurableException.Code.INCOMPATIBLE, "Unknown event schema");
        DurableProtocol.validate("event", event);
        if (event.type().startsWith("session."))
            requireSession(DurableJson.convert(event.data(), SessionView.class).key());
        if (event.type().startsWith("thread.")) {
            var key = DurableJson.convert(event.data(), ThreadView.class).definition().key();
            requireSession(key.session());
            if (!key.threadId().equals(event.threadId()))
                throw new DurableException(
                        DurableException.Code.INVALID, "Thread event ownership mismatch");
        }
        if (!filter.matches(event))
            throw new DurableException(
                    DurableException.Code.INVALID, "Event outside selected scope");
        var next = new BigInteger(event.sequence());
        if (next.compareTo(sequence) <= 0) return;
        if (filter.threadId() == null && !next.equals(sequence.add(BigInteger.ONE)))
            throw new DurableException(
                    DurableException.Code.CURSOR_EXPIRED, "Event gap requires a snapshot");
        var updated = value.deepCopy();
        apply(
                updated,
                new EventInput(
                        event.eventId(), event.type(), event.data(), event.runId(), event.stepId()),
                event.threadId(),
                event.turnId(),
                event.sequence());
        value = updated;
        sequence = next;
        cursor = event.cursor();
    }

    public synchronized JsonNode value() {
        return value.deepCopy();
    }

    public synchronized String cursor() {
        return cursor;
    }

    private void requireSession(SessionKey key) {
        if (!session.equals(key))
            throw new DurableException(
                    DurableException.Code.INVALID, "Projection belongs to another session");
    }

    static ObjectNode empty() {
        return (ObjectNode)
                DurableJson.parse("{\"session\":null,\"threads\":{},\"messages\":{},\"tools\":{}}");
    }

    private void requireResources(JsonNode projection) {
        var resource = projection.path("session");
        if (!resource.isNull())
            requireSession(DurableJson.convert(resource, SessionView.class).key());
        projection
                .path("threads")
                .fields()
                .forEachRemaining(
                        entry -> {
                            var key =
                                    DurableJson.convert(entry.getValue(), ThreadView.class)
                                            .definition()
                                            .key();
                            requireSession(key.session());
                            if (!key.threadId().equals(entry.getKey()))
                                throw new DurableException(
                                        DurableException.Code.INVALID,
                                        "Thread projection ownership mismatch");
                        });
    }

    static void apply(
            ObjectNode projection,
            EventInput event,
            String threadId,
            String turnId,
            String sequence) {
        JsonNode d = event.data();
        if (event.type().startsWith("session.")) {
            projection.set("session", d.deepCopy());
            return;
        }
        if (event.type().startsWith("thread.")) {
            projection.withObject("/threads").set(threadId, d.deepCopy());
            return;
        }
        if (event.type().startsWith("interaction.") || event.type().startsWith("signal.")) {
            var interactions =
                    projection.withObject(
                            event.type().startsWith("signal.") ? "/signals" : "/interactions");
            String id = d.path("id").asText();
            var previous = interactions.get(id);
            var value =
                    previous == null
                            ? (ObjectNode) DurableJson.value(Map.of())
                            : (ObjectNode) previous.deepCopy();
            value.setAll((ObjectNode) d);
            value.put("threadId", threadId);
            value.put("turnId", turnId);
            value.put(
                    "runId",
                    event.runId() != null
                            ? event.runId()
                            : previous == null ? null : previous.path("runId").asText(null));
            value.put(
                    "stepId",
                    event.runId() != null
                            ? event.stepId()
                            : previous == null ? null : previous.path("stepId").asText(null));
            interactions.set(id, value);
        }
        if (event.type().equals("artifact.published")) {
            var artifact = (ObjectNode) d.deepCopy();
            artifact.put("threadId", threadId);
            artifact.put("turnId", turnId);
            artifact.put("runId", event.runId());
            artifact.put("stepId", event.stepId());
            projection.withObject("/artifacts").set(d.path("artifactId").asText(), artifact);
        }
        if (event.type().startsWith("tool."))
            applyTool(projection, event, threadId, turnId, sequence);
        if (!event.type().equals("message.delta") && !event.type().equals("message.completed"))
            return;
        String id = DurableJson.text(d.path("messageId").asText(), "messageId");
        long attempt = d.path("attempt").asLong(0);
        long revision = d.path("revision").asLong(0);
        ObjectNode messages = (ObjectNode) projection.get("messages");
        JsonNode previous = messages.get(id);
        if (previous != null
                && (previous.path("attempt").asLong() > attempt
                        || (previous.path("attempt").asLong() == attempt
                                && previous.path("revision").asLong() > revision))) return;
        boolean same =
                previous != null
                        && previous.path("attempt").asLong() == attempt
                        && previous.path("revision").asLong() == revision;
        if (same && previous.path("complete").asBoolean() && event.type().equals("message.delta"))
            return;
        String text =
                event.type().equals("message.delta")
                        ? (same ? previous.path("text").asText() : "") + d.path("text").asText()
                        : d.path("text").asText();
        messages.set(
                id,
                DurableJson.value(
                        Map.of(
                                "messageId",
                                id,
                                "attempt",
                                attempt,
                                "revision",
                                revision,
                                "text",
                                text,
                                "complete",
                                event.type().equals("message.completed"))));
        var message = (ObjectNode) messages.get(id);
        message.put("turnId", turnId);
        message.put("threadId", threadId);
        if (event.runId() != null) message.put("runId", event.runId());
        if (event.stepId() != null) message.put("stepId", event.stepId());
        message.put(
                "sequence",
                previous != null && previous.has("sequence")
                        ? previous.path("sequence").asText()
                        : sequence);
        for (String field : List.of("requestId", "inputKind", "interactionId")) {
            if (d.has(field)) message.set(field, d.get(field).deepCopy());
            else if (previous != null && previous.has(field))
                message.set(field, previous.get(field).deepCopy());
        }
        if (d.has("message"))
            ((ObjectNode) messages.get(id)).set("message", d.get("message").deepCopy());
    }

    /** Public tool-call identity within a Session; it is not a Hands execution ID. */
    public static String toolId(String threadId, String turnId, String callId) {
        return "tool:"
                + DurableJson.hash(
                        List.of(
                                DurableJson.text(threadId, "threadId"),
                                DurableJson.text(turnId, "turnId"),
                                DurableJson.text(callId, "callId")));
    }

    static String toolId(String threadId, String turnId, JsonNode data) {
        return toolId(
                threadId,
                turnId,
                data.hasNonNull("callId")
                        ? data.path("callId").asText()
                        : data.path("call").path("callId").asText());
    }

    private static void applyTool(
            ObjectNode projection,
            EventInput event,
            String threadId,
            String turnId,
            String sequence) {
        var incoming = event.data();
        String id = toolId(threadId, turnId, incoming);
        var tools = projection.withObject("/tools");
        var previous = tools.get(id);
        ObjectNode data =
                previous == null
                        ? (ObjectNode) DurableJson.value(Map.of())
                        : (ObjectNode) previous.path("data").deepCopy();
        String oldStatus = data.path("status").asText();
        if (event.type().equals("tool.call.arguments")) {
            if (data.has("arguments")) return;
            long attempt = incoming.path("attempt").asLong();
            long revision = incoming.path("revision").asLong();
            long oldAttempt = data.path("attempt").asLong();
            long oldRevision = data.path("revision").asLong();
            if (oldAttempt > attempt || oldAttempt == attempt && oldRevision > revision) return;
            String prefix =
                    oldAttempt == attempt && oldRevision == revision
                            ? data.path("argumentsText").asText()
                            : "";
            data.setAll((ObjectNode) incoming);
            data.remove("text");
            data.put("argumentsText", prefix + incoming.path("text").asText());
            data.put("status", "GENERATING_ARGUMENTS");
        } else if (event.type().equals("tool.call.progress")) {
            if (terminalTool(oldStatus)) return;
            data.put("callId", incoming.path("callId").asText());
            var progress = data.withArray("progress");
            incoming.path("content").forEach(content -> progress.add(content.deepCopy()));
            data.put("status", "RUNNING");
        } else {
            var oldContent = data.get("content");
            data.setAll((ObjectNode) incoming);
            if (incoming.has("arguments") && !data.has("argumentsText"))
                data.put("argumentsText", incoming.path("arguments").toString());
            if (event.type().equals("tool.requested")) {
                var call = incoming.path("call");
                for (String field : List.of("callId", "name", "arguments", "origin"))
                    data.set(field, call.path(field).deepCopy());
                data.put("status", "REQUESTED");
            } else if (event.type().equals("tool.dispatching")) data.put("status", "RUNNING");
            else if (event.type().equals("tool.progress")
                    || event.type().equals("tool.result.accepted"))
                data.put("status", incoming.path("result").path("state").asText());
            else if (event.type().equals("tool.result.applied")) data.put("applied", true);
            String status = data.path("status").asText();
            if (terminalTool(oldStatus) && !terminalTool(status)
                    || "REQUESTED".equals(status)
                            && !oldStatus.isEmpty()
                            && !"GENERATING_ARGUMENTS".equals(oldStatus)) {
                data.put("status", oldStatus);
                if (oldContent != null) data.set("content", oldContent);
            }
        }
        var value =
                (ObjectNode)
                        DurableJson.value(
                                new EventInput(
                                        event.eventId(),
                                        event.type(),
                                        data,
                                        event.runId() != null
                                                ? event.runId()
                                                : previous == null
                                                        ? null
                                                        : previous.path("runId").asText(null),
                                        event.runId() != null
                                                ? event.stepId()
                                                : previous == null
                                                        ? null
                                                        : previous.path("stepId").asText(null)));
        value.put("threadId", threadId);
        value.put("turnId", turnId);
        value.put("sequence", previous == null ? sequence : previous.path("sequence").asText());
        tools.set(id, value);
    }

    private static boolean terminalTool(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED", "DENIED").contains(status);
    }
}

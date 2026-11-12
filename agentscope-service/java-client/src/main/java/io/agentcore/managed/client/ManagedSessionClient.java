/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.client;

import static io.agentcore.managed.client.ServiceClient.key;
import static io.agentcore.managed.client.ServiceClient.query;
import static io.agentcore.managed.client.ServiceClient.segment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native Managed resources, Turns and Input receipts, sharing the original client's transport. */
public final class ManagedSessionClient {
    private static final String ROOT = "/v1/sessions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ServiceClient transport;

    ManagedSessionClient(ServiceClient transport) {
        this.transport = transport;
    }

    public JsonNode create(ObjectNode definition, String idempotencyKey)
            throws IOException, InterruptedException {
        return transport.request(ROOT, "POST", definition, key(idempotencyKey));
    }

    public JsonNode sessions(String after, int limit, String lifecycle, String agentId)
            throws IOException, InterruptedException {
        return get(
                ROOT
                        + query(
                                "after",
                                after,
                                "limit",
                                limit(limit, 256),
                                "lifecycle",
                                lifecycle,
                                "agent_id",
                                agentId));
    }

    public JsonNode checkpoints(String session, String after, String through, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/checkpoints"
                        + query("after", after, "through", through, "limit", limit(limit, 100)));
    }

    public JsonNode checkpoint(String session, String checkpoint)
            throws IOException, InterruptedException {
        return get(sessionPath(session) + "/checkpoints/" + segment(checkpoint));
    }

    public JsonNode restoreCheckpoint(String session, String checkpoint, String idempotencyKey)
            throws IOException, InterruptedException {
        segment(checkpoint);
        return transport.request(
                sessionPath(session) + "/checkpoints/restore",
                "POST",
                MAPPER.createObjectNode().put("checkpoint_id", checkpoint),
                key(idempotencyKey));
    }

    /** Target must be a separately registered, empty Managed Session. */
    public JsonNode forkCheckpoint(
            String session, String target, String checkpoint, String idempotencyKey)
            throws IOException, InterruptedException {
        segment(target);
        segment(checkpoint);
        if (session.equals(target)) throw new IllegalArgumentException("Fork target must differ");
        return transport.request(
                sessionPath(session) + "/fork",
                "POST",
                MAPPER.createObjectNode()
                        .put("checkpoint_id", checkpoint)
                        .put("target_session_id", target),
                key(idempotencyKey));
    }

    public JsonNode checkpointOperation(String session, String operation)
            throws IOException, InterruptedException {
        var receipt = get(sessionPath(session) + "/checkpoint-operations/" + segment(operation));
        if (!session.equals(receipt.path("source_session_id").asText()))
            throw new IOException("Checkpoint operation belongs to another Session");
        return receipt;
    }

    public JsonNode cancelCheckpointOperation(String session, String operation)
            throws IOException, InterruptedException {
        return transport.request(
                sessionPath(session) + "/checkpoint-operations/" + segment(operation) + "/cancel",
                "POST",
                null,
                null);
    }

    public JsonNode session(String id) throws IOException, InterruptedException {
        return get(sessionPath(id));
    }

    public JsonNode update(String id, ObjectNode metadata)
            throws IOException, InterruptedException {
        return transport.request(sessionPath(id), "POST", metadata, null);
    }

    public JsonNode sendEvents(String session, JsonNode events, String idempotencyKey)
            throws IOException, InterruptedException {
        return transport.request(
                sessionPath(session) + "/events",
                "POST",
                MAPPER.createObjectNode().set("events", events),
                key(idempotencyKey));
    }

    public JsonNode submit(String session, JsonNode input, String idempotencyKey)
            throws IOException, InterruptedException {
        var event = MAPPER.createObjectNode().put("type", "user.message");
        event.set("content", inputContent(input));
        event.set("managed_agents", MAPPER.createObjectNode().put("intent", "new_turn"));
        return receipt(sendEvents(session, MAPPER.createArrayNode().add(event), idempotencyKey));
    }

    public JsonNode submitTurn(String session, JsonNode input)
            throws IOException, InterruptedException {
        return submit(session, input, UUID.randomUUID().toString());
    }

    public JsonNode submitMessage(String session, String message, String idempotencyKey)
            throws IOException, InterruptedException {
        return submit(session, MAPPER.createObjectNode().put("message", message), idempotencyKey);
    }

    private static JsonNode inputContent(JsonNode input) {
        if (input == null
                || !input.isObject()
                || input.size() != 1
                || input.has("message") == input.has("input"))
            throw new IllegalArgumentException(
                    "Provide exactly one of message or input; unsupported input fields");
        if (input.path("message").isTextual())
            return MAPPER.createArrayNode()
                    .add(
                            MAPPER.createObjectNode()
                                    .put("type", "text")
                                    .put("text", input.path("message").asText()));
        if (!input.path("input").isArray()
                || input.path("input").size() != 1
                || !"user".equals(input.path("input").get(0).path("role").asText()))
            throw new IllegalArgumentException("A single user message is required");
        var message = input.path("input").get(0);
        var content = message.path("content");
        if (message.size() != 2 || !content.isArray() || content.isEmpty())
            throw new IllegalArgumentException("Only text content is supported");
        for (var block : content)
            if (!block.isObject()
                    || block.size() != 2
                    || !"text".equals(block.path("type").asText())
                    || !block.path("text").isTextual())
                throw new IllegalArgumentException("Only text content is supported");
        return content;
    }

    private static JsonNode receipt(JsonNode response) {
        var event = response.path("data").get(0);
        var ext = event.path("managed_agents");
        var receipt = (ObjectNode) ext.path("receipt").deepCopy();
        receipt.put("event_id", event.path("id").asText());
        receipt.put("turn_id", ext.path("turn_id").asText());
        if (ext.has("input_id")) receipt.put("input_type", ext.path("intent").asText());
        return receipt;
    }

    public JsonNode turn(String session, String turn) throws IOException, InterruptedException {
        return get(turnPath(session, turn));
    }

    public JsonNode turns(String session, String after, int limit, String order, String status)
            throws IOException, InterruptedException {
        return turns(session, after, limit, order, status, null);
    }

    public JsonNode turns(
            String session,
            String after,
            int limit,
            String order,
            String status,
            String inputEventId)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/turns"
                        + query(
                                "after",
                                after,
                                "limit",
                                limit(limit, 256),
                                "order",
                                order,
                                "status",
                                status,
                                "input_event_id",
                                inputEventId));
    }

    public JsonNode input(
            String session,
            String turn,
            String type,
            JsonNode payload,
            String actionId,
            String idempotencyKey)
            throws IOException, InterruptedException {
        if (!Set.of("steer", "inject", "cancel").contains(type) || actionId != null)
            throw new IllegalArgumentException("unsupported_capability: Input type");
        var event =
                MAPPER.createObjectNode()
                        .put("type", "cancel".equals(type) ? "user.interrupt" : "user.message");
        event.set(
                "managed_agents",
                MAPPER.createObjectNode().put("intent", type).put("turn_id", turn));
        if (!"cancel".equals(type)) event.set("content", inputContent(payload));
        return receipt(sendEvents(session, MAPPER.createArrayNode().add(event), idempotencyKey));
    }

    public JsonNode steer(String session, String turn, String message, String idempotencyKey)
            throws IOException, InterruptedException {
        return input(
                session,
                turn,
                "steer",
                MAPPER.createObjectNode().put("message", message),
                null,
                idempotencyKey);
    }

    public JsonNode inject(String session, String turn, String message, String idempotencyKey)
            throws IOException, InterruptedException {
        return input(
                session,
                turn,
                "inject",
                MAPPER.createObjectNode().put("message", message),
                null,
                idempotencyKey);
    }

    public JsonNode cancelTurn(String session, String turn, String idempotencyKey)
            throws IOException, InterruptedException {
        return input(session, turn, "cancel", null, null, idempotencyKey);
    }

    public JsonNode output(String session, String turn, String after, int limit)
            throws IOException, InterruptedException {
        return get(
                turnPath(session, turn)
                        + "/output"
                        + query("after", after, "limit", limit(limit, 256)));
    }

    public List<JsonNode> finalOutput(String session, String turn, Duration timeout)
            throws IOException, InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Output timeout must be positive");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Set.of("completed", "failed", "cancelled")
                .contains(
                        readOutputResource(turnPath(session, turn), deadline)
                                .path("status")
                                .asText())) {
            pauseOutput(deadline);
        }
        var result = new ArrayList<JsonNode>();
        var cursors = new HashSet<String>();
        String after = null;
        while (true) {
            var page =
                    readOutputResource(
                            turnPath(session, turn)
                                    + "/output"
                                    + query("after", after, "limit", 100),
                            deadline);
            var cursor = page.path("next_cursor");
            boolean more = page.path("has_more").asBoolean();
            if (!page.path("data").isArray()
                    || !page.path("output_available").isBoolean()
                    || (!page.path("output_available").asBoolean()
                            && (!page.path("data").isEmpty() || more))
                    || !page.path("has_more").isBoolean()
                    || (more
                            ? !cursor.isTextual()
                                    || cursor.asText().isBlank()
                                    || !cursors.add(cursor.asText())
                            : !cursor.isNull())) throw new IOException("Invalid final output page");
            page.path("data").forEach(result::add);
            if (!more) return List.copyOf(result);
            after = cursor.asText();
        }
    }

    private JsonNode readOutputResource(String path, long deadline)
            throws IOException, InterruptedException {
        while (true) {
            try {
                return transport.request(path, "GET", null, null, Map.of(), outputBudget(deadline));
            } catch (ServiceAPIException error) {
                if (error.status() != 503) throw error;
                pauseOutput(deadline);
            }
        }
    }

    private static Duration outputBudget(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Output wait timed out; execution continues");
        return Duration.ofNanos(remaining);
    }

    private static void pauseOutput(long deadline) throws IOException, InterruptedException {
        Thread.sleep(Math.min(250, Math.max(1, outputBudget(deadline).toMillis())));
    }

    public JsonNode inputs(String session, String turn, String after, int limit)
            throws IOException, InterruptedException {
        return inputs(session, turn, after, limit, null, null);
    }

    public JsonNode inputs(
            String session, String turn, String after, int limit, String status, String eventId)
            throws IOException, InterruptedException {
        return get(
                turnPath(session, turn)
                        + "/inputs"
                        + query(
                                "after",
                                after,
                                "limit",
                                limit(limit, 256),
                                "status",
                                status,
                                "event_id",
                                eventId));
    }

    public JsonNode inputReceipt(String session, String turn, String input)
            throws IOException, InterruptedException {
        var receipt = get(turnPath(session, turn) + "/inputs/" + segment(input));
        if (!turn.equals(receipt.path("turn_id").asText()))
            throw new IOException("Input receipt belongs to another Turn");
        return receipt;
    }

    public JsonNode capabilities(String session) throws IOException, InterruptedException {
        return get(sessionPath(session) + "/capabilities");
    }

    public JsonNode webhook(
            String session, String url, List<String> eventTypes, String idempotencyKey)
            throws IOException, InterruptedException {
        var body = MAPPER.createObjectNode().put("url", url);
        if (eventTypes != null) body.set("event_types", MAPPER.valueToTree(eventTypes));
        return transport.request(
                sessionPath(session) + "/webhooks", "POST", body, key(idempotencyKey));
    }

    public JsonNode webhooks(String session, String after, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/webhooks"
                        + query("after", after, "limit", limit(limit, 100)));
    }

    public JsonNode webhookDeliveries(String session, String webhook, String after, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/webhooks/"
                        + segment(webhook)
                        + "/deliveries"
                        + query("after", after, "limit", limit(limit, 100)));
    }

    public JsonNode deleteWebhook(String session, String webhook)
            throws IOException, InterruptedException {
        return transport.request(
                sessionPath(session) + "/webhooks/" + segment(webhook), "DELETE", null, null);
    }

    public JsonNode retryWebhook(String session, String webhook)
            throws IOException, InterruptedException {
        return transport.request(
                sessionPath(session) + "/webhooks/" + segment(webhook) + "/retry",
                "POST",
                MAPPER.createObjectNode(),
                null);
    }

    public JsonNode turnCapabilities(String session, String turn)
            throws IOException, InterruptedException {
        return get(turnPath(session, turn) + "/capabilities");
    }

    public JsonNode snapshot(String session, String turn, int limit)
            throws IOException, InterruptedException {
        return get(scopePath(session, turn) + "/snapshot" + query("limit", limit(limit, 256)));
    }

    /** A Turn snapshot also pages through this Session route; snapshot_id retains its Turn filter. */
    public JsonNode resources(String session, String snapshot, String kind, String after, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/resources/"
                        + segment(kind)
                        + query(
                                "snapshot_id",
                                snapshot,
                                "after",
                                after,
                                "limit",
                                limit(limit, 256)));
    }

    public JsonNode history(String session, Map<String, List<String>> parameters)
            throws IOException, InterruptedException {
        var query = new ArrayList<String>();
        parameters.forEach(
                (name, values) ->
                        values.forEach(
                                value ->
                                        query.add(
                                                URLEncoder.encode(name, StandardCharsets.UTF_8)
                                                        + "="
                                                        + URLEncoder.encode(
                                                                value, StandardCharsets.UTF_8))));
        return get(
                sessionPath(session)
                        + "/events"
                        + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    public JsonNode events(String session, String after, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/events"
                        + query("page", after, "limit", limit(limit, 256)));
    }

    public EventStream stream(String session, String after)
            throws IOException, InterruptedException {
        if (after != null && !after.isBlank())
            throw new IllegalArgumentException(
                    "unsupported_capability: SSE resume is not supported");
        return new EventStream(
                session, transport.eventStream(sessionPath(session) + "/events/stream", null));
    }

    public JsonNode usage(String session, String turn) throws IOException, InterruptedException {
        return get(scopePath(session, turn) + "/usage");
    }

    public JsonNode budget(String session) throws IOException, InterruptedException {
        return get(sessionPath(session) + "/budget");
    }

    public JsonNode setBudget(String session, JsonNode limits, Long revision)
            throws IOException, InterruptedException {
        if (revision != null && revision < 1)
            throw new IllegalArgumentException("Budget revision must be positive");
        return transport.request(
                sessionPath(session) + "/budget",
                "PUT",
                limits,
                null,
                revision == null ? Map.of() : Map.of("If-Match", revision.toString()));
    }

    public JsonNode toolTasks(String session, String turn, String after, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/tool-tasks"
                        + query("turn", turn, "after", after, "limit", limit(limit, 256)));
    }

    public JsonNode lifecycle(String session, String kind, String idempotencyKey)
            throws IOException, InterruptedException {
        if (!Set.of("archive", "restore", "delete").contains(kind))
            throw new IllegalArgumentException("Unsupported lifecycle kind");
        return transport.request(
                sessionPath(session) + ("delete".equals(kind) ? "" : "/" + kind),
                "delete".equals(kind) ? "DELETE" : "POST",
                null,
                key(idempotencyKey));
    }

    public JsonNode lifecycleReceipt(String session, String command)
            throws IOException, InterruptedException {
        var receipt = get(sessionPath(session) + "/lifecycle/" + segment(command));
        if (!session.equals(receipt.path("session_id").asText()))
            throw new IOException("Lifecycle receipt belongs to another Session");
        return receipt;
    }

    public JsonNode uploadFile(
            String session, String name, byte[] bytes, String mediaType, String idempotencyKey)
            throws IOException, InterruptedException {
        return transport.upload(
                sessionPath(session) + "/files", name, bytes, mediaType, idempotencyKey);
    }

    public JsonNode files(String session, int offset, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/files"
                        + query("offset", offset, "limit", limit(limit, 100)));
    }

    public JsonNode file(String session, String file) throws IOException, InterruptedException {
        return get(sessionPath(session) + "/files/" + segment(file));
    }

    public byte[] fileContent(String session, String file)
            throws IOException, InterruptedException {
        return transport.content(sessionPath(session) + "/files/" + segment(file) + "/content");
    }

    public JsonNode publishArtifact(String session, JsonNode artifact, String idempotencyKey)
            throws IOException, InterruptedException {
        return transport.request(
                sessionPath(session) + "/artifacts", "POST", artifact, key(idempotencyKey));
    }

    public JsonNode artifacts(String session, String turn, String after, String snapshot, int limit)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/artifacts"
                        + query(
                                "turn",
                                turn,
                                "after",
                                after,
                                "snapshot_id",
                                snapshot,
                                "limit",
                                limit(limit, 256)));
    }

    public JsonNode artifact(String session, String artifact, String snapshot)
            throws IOException, InterruptedException {
        return get(
                sessionPath(session)
                        + "/artifacts/"
                        + segment(artifact)
                        + query("snapshot_id", snapshot));
    }

    public byte[] artifactContent(String session, String artifact)
            throws IOException, InterruptedException {
        return transport.content(
                sessionPath(session) + "/artifacts/" + segment(artifact) + "/content");
    }

    public JsonNode deleteArtifact(String session, String artifact)
            throws IOException, InterruptedException {
        return transport.request(
                sessionPath(session) + "/artifacts/" + segment(artifact), "DELETE", null, null);
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        return transport.request(path, "GET", null, null);
    }

    private static String sessionPath(String id) {
        return ROOT + "/" + segment(id);
    }

    private static String turnPath(String session, String turn) {
        return sessionPath(session) + "/turns/" + segment(turn);
    }

    private static String scopePath(String session, String turn) {
        return turn == null || turn.isBlank() ? sessionPath(session) : turnPath(session, turn);
    }

    private static int limit(int value, int maximum) {
        if (value < 1 || value > maximum) throw new IllegalArgumentException("Invalid page limit");
        return value;
    }

    public static final class EventStream implements AutoCloseable {
        private final String session;
        private final ServiceClient.EventStream source;

        private EventStream(String session, ServiceClient.EventStream source) {
            this.session = session;
            this.source = source;
        }

        public JsonNode nextEvent() throws IOException {
            var event = source.nextEvent();
            if (event != null
                    && event.has("managed_agents")
                    && !session.equals(event.path("managed_agents").path("session_id").asText())) {
                close();
                throw new IOException("Event belongs to another Session");
            }
            return event;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}

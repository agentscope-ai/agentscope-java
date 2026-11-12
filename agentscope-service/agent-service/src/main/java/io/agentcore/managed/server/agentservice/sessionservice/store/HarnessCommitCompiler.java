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
package io.agentcore.managed.server.agentservice.sessionservice.store;

import static io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore.conflict;
import static io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore.invalid;
import static io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore.text;
import static io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore.uint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedCommitPlan;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ModelCallResult;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.SessionCommit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Converts explicit protocol facts to the existing metadata transaction, without parsing native state. */
final class HarnessCommitCompiler {
    private final ManagedJournalStore journal;
    private final RuntimeObjectStore objects;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    HarnessCommitCompiler(
            ManagedJournalStore journal,
            RuntimeObjectStore objects,
            ObjectMapper mapper,
            JdbcTemplate jdbc) {
        this.journal = journal;
        this.objects = objects;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    ManagedCommitPlan prepare(ManagedScope scope, JsonNode request) {
        var changes = request.required("changes");
        var state = request.required("nativeState");
        var cursor = text(state, "nativeCursor");
        for (String group :
                List.of(
                        "toolCalls",
                        "resultApplications",
                        "inputApplications",
                        "modelUpdates",
                        "interactionUpdates")) {
            var identities = new HashSet<String>();
            for (var item : changes.path(group)) {
                if (item.has("position")
                        && !cursor.equals(text(item.required("position"), "nativeCursor")))
                    throw invalid("Native position differs from submitted state");
                String key =
                        switch (group) {
                            case "toolCalls", "resultApplications" -> "nativeCallId";
                            case "inputApplications" -> "inputId";
                            case "modelUpdates" -> "modelCallId";
                            default -> "actionId";
                        };
                if (!identities.add(text(item, key) + item.path("phase").asText()))
                    throw invalid("Duplicate change identity");
            }
        }
        var operationIds = new HashSet<String>();
        for (var operation : changes.path("toolOperations"))
            if (!operationIds.add(text(operation, "operationId")))
                throw invalid("Duplicate operation identity");
        if (state.has("checkpointRef")) read(scope, state.get("checkpointRef"));
        for (var ref : state.path("journalRefs")) read(scope, ref);
        if (!state.has("checkpointRef") && state.path("journalRefs").isEmpty())
            throw invalid("Durable native state is required");
        String turn = text(request, "turnId"), commit = text(request, "commitId");
        long epoch = uint(request.required("fence"), "epoch");
        var head = journal.head(scope);
        long sequence =
                Math.addExact(
                        head.sequence(),
                        1
                                + changes.path("interactionUpdates").size()
                                + changes.path("modelUpdates").size());
        var calls = new ArrayList<ManagedCommitPlan.ToolCall>();
        for (var call : changes.path("toolCalls"))
            calls.add(new ManagedCommitPlan.ToolCall(text(call, "nativeCallId"), true));
        for (var applied : changes.path("resultApplications"))
            calls.add(new ManagedCommitPlan.ToolCall(text(applied, "nativeCallId"), false));
        var intents = new ArrayList<SessionCommit.ToolIntent>();
        var bindings = new ArrayList<ManagedCommitPlan.ToolBinding>();
        for (var operation : changes.path("toolOperations")) {
            String id =
                    "tool_"
                            + ManagedScope.fingerprint(
                                    scope.messageGroup(), turn, text(operation, "operationId"));
            if (!id.equals(text(operation, "taskId")) || !id.equals(text(operation, "executionId")))
                throw invalid("Operation identity differs");
            if (operation.required("nativeCallIds").size() != 1 || operation.has("batch"))
                throw conflict("unsupported_batch_operation");
            intents.add(
                    new SessionCommit.ToolIntent(
                            text(operation, "operationId"),
                            text(operation, "toolName"),
                            content(scope, operation.required("arguments")).toString(),
                            new String(
                                    read(scope, operation.required("configurationRef")),
                                    StandardCharsets.UTF_8)));
            bindings.add(
                    new ManagedCommitPlan.ToolBinding(
                            operation.get("nativeCallIds").get(0).asText(), List.of()));
        }
        var inputs = new ArrayList<SessionCommit.InputReceipt>();
        for (var input : changes.path("inputApplications"))
            inputs.add(
                    new SessionCommit.InputReceipt(
                            text(input, "inputId"),
                            text(input, "status"),
                            input.path("reason").isTextual()
                                    ? input.get("reason").asText()
                                    : null));
        SessionCommit.Suspension suspension = null;
        if (changes.has("suspension")) {
            var wait = changes.get("suspension");
            suspension =
                    new SessionCommit.Suspension(
                            text(wait, "suspensionId"),
                            strings(wait.required("pendingTaskIds")),
                            strings(wait.required("pendingActionIds")),
                            "WAIT_TOOLS".equals(text(wait, "resumePhase"))
                                    ? "APPLY_TOOL_RESULTS"
                                    : "APPLY_INPUTS");
        }
        var pending =
                suspension == null
                        ? null
                        : new ManagedCommitPlan.PendingState(
                                suspension.taskIds(), suspension.actionIds(), null);
        // The checkpoint wrapper contains only the opaque native manifest and explicit continuation
        // facts.
        String id = "checkpoint_" + ManagedScope.fingerprint(scope.messageGroup(), commit);
        var identity =
                new ManagedCheckpointObjects.Identity(1, id, scope, turn, turn, epoch, sequence);
        String stateText = state.toString();
        var image =
                new ManagedCheckpointObjects.Image(
                        identity,
                        ManagedScope.fingerprint(stateText),
                        RuntimeObjectStore.sha256(bytes(Map.of())),
                        json(Map.of("stateJson", stateText)),
                        Map.of(
                                "scope",
                                scope,
                                "turnId",
                                turn,
                                "epoch",
                                epoch,
                                "acceptedThrough",
                                sequence),
                        null);
        var object = objects.put(scope.messageGroup(), "checkpoint", id, bytes(image));
        var reference =
                new ManagedCheckpointObjects.Reference(
                        identity,
                        scope.messageGroup() + "/checkpoint/" + id,
                        object.sha256(),
                        object.size(),
                        image.stateHash(),
                        object.backendId());
        var checkpoint =
                new ManagedCommitPlan.Checkpoint(reference, System.currentTimeMillis(), pending);
        var facts = new ArrayList<ManagedCommitPlan.PublicFact>();
        for (var fact : request.path("publicFacts"))
            facts.add(
                    new ManagedCommitPlan.PublicFact(
                            text(fact, "factId"),
                            text(fact, "type"),
                            map(content(scope, fact.required("content")))));
        if (changes.has("terminal")) {
            var value = content(scope, changes.required("terminal").required("output"));
            String messageId =
                    changes.required("terminal").path("messageId").asText("output_" + turn);
            String itemId = "item_" + ManagedScope.fingerprint(turn, messageId);
            Object publicContent =
                    value.isArray()
                            ? mapper.convertValue(value, Object.class)
                            : List.of(
                                    Map.of(
                                            "type",
                                            "text",
                                            "text",
                                            value.isTextual() ? value.asText() : value.toString()));
            facts.add(
                    new ManagedCommitPlan.PublicFact(
                            commit + ":output",
                            "item.finalized",
                            Map.of(
                                    "schema_version",
                                    1,
                                    "turn_id",
                                    turn,
                                    "item_id",
                                    itemId,
                                    "final_output",
                                    true,
                                    "item",
                                    Map.of(
                                            "id",
                                            itemId,
                                            "message_id",
                                            messageId,
                                            "type",
                                            "message",
                                            "role",
                                            "assistant",
                                            "status",
                                            "completed",
                                            "content",
                                            publicContent))));
        }
        var platformChanges = new ArrayList<ManagedCommitPlan.Change>();
        var modelRequests = new ArrayList<ManagedCommitPlan.ModelRequest>();
        var modelOutcomes = new ArrayList<ManagedCommitPlan.ModelOutcome>();
        long position = head.sequence();
        for (var update : changes.path("modelUpdates")) {
            ++position;
            String call = text(update, "modelCallId");
            ManagedCommitPlan.ModelUsage usage = null;
            var modelFacts = new ArrayList<ManagedCommitPlan.PublicFact>();
            if ("dispatch".equals(text(update, "phase"))) {
                var requestBytes = read(scope, update.required("requestRef"));
                if (!CanonicalJson.hash(parse(new String(requestBytes, StandardCharsets.UTF_8)))
                        .equals(text(update, "requestHash")))
                    throw invalid("Model request hash differs");
                if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.model_call WHERE source_scope=? AND"
                                        + " turn_id=? AND call_id=? AND status='reserved'",
                                Long.class,
                                scope.messageGroup(),
                                turn,
                                call)
                        != 1) throw conflict("model_reservation_required");
                modelRequests.add(new ManagedCommitPlan.ModelRequest(call, position, null));
                modelFacts.add(
                        new ManagedCommitPlan.PublicFact(
                                "model:" + turn + ":" + call + ":start",
                                "model.started",
                                Map.of(
                                        "schema_version",
                                        1,
                                        "turn_id",
                                        turn,
                                        "model_call_id",
                                        call)));
            } else {
                var rows =
                        jdbc.queryForList(
                                "SELECT request_sequence,request FROM ma.harness_model WHERE"
                                        + " scope_key=? AND turn_id=? AND model_call_id=?",
                                scope.messageGroup(),
                                turn,
                                call);
                if (rows.size() != 1) throw conflict("model_call_conflict");
                var dispatch = parse((String) rows.get(0).get("request"));
                var requestBytes = read(scope, dispatch.required("requestRef"));
                var requestRef =
                        objects.put(
                                scope.messageGroup(),
                                "model-request",
                                ManagedScope.fingerprint(turn, call),
                                requestBytes);
                var nativeResult =
                        parse(
                                new String(
                                        read(scope, update.required("resultRef")),
                                        StandardCharsets.UTF_8));
                var result =
                        new ModelCallResult(
                                1,
                                scope.messageGroup(),
                                turn,
                                call,
                                text(dispatch, "model"),
                                text(dispatch, "purpose"),
                                text(dispatch, "requestHash"),
                                requestRef,
                                ((Number) rows.get(0).get("request_sequence")).longValue(),
                                position,
                                head.checkpointSequence(),
                                text(update, "status"),
                                List.of(
                                        nativeResult.isObject()
                                                ? map(nativeResult)
                                                : Map.of(
                                                        "native",
                                                        mapper.convertValue(
                                                                nativeResult, Object.class))),
                                Map.of());
                var referenceResult =
                        objects.put(
                                scope.messageGroup(),
                                "model-result",
                                ManagedScope.fingerprint(turn, call),
                                bytes(result));
                modelOutcomes.add(
                        new ManagedCommitPlan.ModelOutcome(
                                result, referenceResult, text(dispatch, "requestHash")));
                var values = new LinkedHashMap<String, Object>();
                if (update.has("usage"))
                    update.get("usage")
                            .fields()
                            .forEachRemaining(
                                    field ->
                                            values.put(
                                                    field.getKey(),
                                                    uint(update.get("usage"), field.getKey())));
                usage =
                        new ManagedCommitPlan.ModelUsage(
                                call, text(update, "status"), values, update.has("usage"));
                var publicUsage = new LinkedHashMap<String, Object>();
                if (update.has("usage")) {
                    publicUsage.put("inputTokens", values.get("inputTokens"));
                    publicUsage.put("outputTokens", values.get("outputTokens"));
                    publicUsage.put(
                            "cachedTokens", values.getOrDefault("cacheReadInputTokens", 0L));
                    publicUsage.put(
                            "cacheCreationTokens",
                            values.getOrDefault("cacheWriteInputTokens", 0L));
                }
                modelFacts.add(
                        new ManagedCommitPlan.PublicFact(
                                "model:" + turn + ":" + call + ":end",
                                "model.completed",
                                Map.of(
                                        "schema_version",
                                        1,
                                        "turn_id",
                                        turn,
                                        "model_call_id",
                                        call,
                                        "status",
                                        text(update, "status"),
                                        "usage",
                                        publicUsage)));
            }
            platformChanges.add(
                    new ManagedCommitPlan.Change(
                            position,
                            List.of(),
                            null,
                            null,
                            null,
                            usage,
                            null,
                            null,
                            modelFacts,
                            null));
        }
        for (var update : changes.path("interactionUpdates")) {
            ++position;
            boolean opened = "requested".equals(text(update, "phase"));
            var interaction =
                    new ManagedCommitPlan.Interaction(
                            text(update, "actionId"),
                            opened ? text(update, "actionType") : null,
                            opened ? content(scope, update.required("request")).toString() : null,
                            opened);
            platformChanges.add(
                    new ManagedCommitPlan.Change(
                            position,
                            List.of(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            interaction,
                            List.of(),
                            null));
        }
        platformChanges.add(
                new ManagedCommitPlan.Change(
                        sequence, calls, null, null, null, null, checkpoint, null, facts, null));
        var recoveries = new ArrayList<SessionCommit.RecoveryFact>();
        for (var change : platformChanges)
            recoveries.add(
                    new SessionCommit.RecoveryFact(
                            1,
                            commit + ":" + change.sequence(),
                            change.sequence(),
                            System.currentTimeMillis(),
                            "harness/checkpoint",
                            turn,
                            turn,
                            true,
                            stateText));
        var envelope =
                new SessionCommit(
                        1,
                        scope,
                        turn,
                        epoch,
                        text(request.required("fence"), "executionToken"),
                        text(request.required("fence").required("owner"), "incarnationId"),
                        commit,
                        head.sequence(),
                        recoveries,
                        intents,
                        inputs,
                        changes.has("terminal") ? text(changes.get("terminal"), "status") : null,
                        suspension);
        // Generic state is read from harness_head. The platform index records its manifest as the
        // recovery fact.
        return new ManagedCommitPlan(
                envelope,
                CanonicalJson.hash(request),
                platformChanges,
                bindings,
                null,
                modelRequests,
                modelOutcomes,
                List.of(),
                "harness");
    }

    void registerModels(ManagedScope scope, String turn, JsonNode changes, ManagedCommitPlan plan) {
        for (var update : changes.path("modelUpdates"))
            if ("dispatch".equals(text(update, "phase"))) {
                String id = text(update, "modelCallId");
                var request =
                        plan.modelRequests().stream()
                                .filter(item -> item.callId().equals(id))
                                .findFirst()
                                .orElseThrow();
                jdbc.update(
                        "INSERT INTO"
                            + " ma.harness_model(scope_key,turn_id,model_call_id,request_sequence,request)"
                            + " VALUES(?,?,?,?,?)",
                        scope.messageGroup(),
                        turn,
                        id,
                        request.sequence(),
                        update.toString());
            }
    }

    void validateResults(ManagedScope scope, String turn, JsonNode changes) {
        for (var update : changes.path("interactionUpdates"))
            if ("resolved".equals(text(update, "phase"))) {
                String inputId = text(update, "inputId"), actionId = text(update, "actionId");
                boolean applied = false;
                for (var input : changes.path("inputApplications"))
                    if (inputId.equals(text(input, "inputId"))
                            && "applied".equals(text(input, "status"))) applied = true;
                if (!applied
                        || jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ma.input WHERE scope_key=? AND"
                                                + " turn_id=? AND input_id=? AND action_id=? AND"
                                                + " type='REPLY' AND status='accepted'",
                                        Long.class,
                                        scope.messageGroup(),
                                        turn,
                                        inputId,
                                        actionId)
                                != 1) throw conflict("interaction_input_mismatch");
            }
        for (var applied : changes.path("resultApplications")) {
            var tasks = journal.toolsForNativeCall(scope, turn, text(applied, "nativeCallId"));
            if (tasks.isEmpty()) throw conflict("tool_not_found");
            var expected = new ArrayList<String>();
            for (var task : tasks) {
                if (task.result() == null) throw conflict("result_not_accepted");
                expected.add("result_" + ManagedScope.fingerprint(task.id(), task.executionId()));
            }
            var actual = strings(applied.required("resultIds"));
            if (actual.size() != expected.size()
                    || !new HashSet<>(actual).equals(new HashSet<>(expected)))
                throw conflict("result_identity_conflict");
        }
    }

    ObjectNode result(ManagedScope scope, ManagedJournalStore.ToolTask task) {
        var result = mapper.createObjectNode();
        result.put("resultId", "result_" + ManagedScope.fingerprint(task.id(), task.executionId()));
        result.put("taskId", task.id());
        result.put("executionId", task.executionId());
        result.put("status", task.status());
        result.putObject("content").set("inline", parse(task.result()));
        result.putArray("artifacts");
        var output = result.putObject("output");
        var cursor =
                journal.toolEvents()
                        .cursor(scope, task.id())
                        .orElseThrow(() -> conflict("result_not_accepted"));
        var events = journal.toolEvents().read(scope, task.id(), cursor.consumedSeq() - 1, 1);
        if (events.size() != 1 || !"tool.result".equals(events.get(0).type()))
            throw conflict("result_not_accepted");
        var event = events.get(0);
        String mode = String.valueOf(event.data().get("log_status"));
        output.put("mode", "not_supported".equals(mode) ? "none" : mode);
        output.put("throughSourceSeq", Long.toString(event.sourceSeq()));
        output.put("byteLength", String.valueOf(event.data().get("output_offset")));
        if ("gapped".equals(mode)) {
            var gaps = output.putArray("gapEventIds");
            long after = 0;
            while (after < cursor.consumedSeq()) {
                var page = journal.toolEvents().read(scope, task.id(), after, 256);
                if (page.isEmpty()) throw conflict("tool_output_pending");
                for (var item : page) {
                    if ("tool.output_gap".equals(item.type())) gaps.add(item.eventId());
                    if (gaps.size() > 256) throw conflict("recovery_limit_exceeded");
                    after = item.sourceSeq();
                }
            }
        }
        return result;
    }

    byte[] read(ManagedScope scope, JsonNode ref) {
        if (!mapper.valueToTree(scope).equals(ref.required("scope")))
            throw invalid("Object scope differs");
        return objects.read(
                new RuntimeObjectStore.Reference(
                        text(ref, "backendId"),
                        scope.messageGroup(),
                        "harness-object",
                        ManagedScope.fingerprint(text(ref, "key")),
                        text(ref, "sha256"),
                        uint(ref, "size")));
    }

    private JsonNode content(ManagedScope scope, JsonNode value) {
        if (value.has("inline") == value.has("ref"))
            throw invalid("Content needs exactly one representation");
        return value.has("inline")
                ? value.get("inline")
                : parse(new String(read(scope, value.get("ref")), StandardCharsets.UTF_8));
    }

    private List<String> strings(JsonNode value) {
        var result = new ArrayList<String>();
        for (var item : value) result.add(item.asText());
        return List.copyOf(result);
    }

    private Map<String, Object> map(JsonNode value) {
        if (!value.isObject()) throw invalid("Public fact must be an object");
        return mapper.convertValue(
                value,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception invalid) {
            throw invalid("Invalid JSON content");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw invalid("Invalid JSON content");
        }
    }

    private byte[] bytes(Object value) {
        return json(value).getBytes(StandardCharsets.UTF_8);
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Platform model outcome indexes and finalization transactions; native decoding belongs to the adapter. */
public final class ManagedModelResults {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RuntimeObjectStore objects;

    ManagedModelResults(JdbcTemplate jdbc, ObjectMapper mapper, RuntimeObjectStore objects) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.objects = objects;
    }

    public record RequestPosition(long requestSequence, long checkpointSequence) {}

    RequestPosition requestPosition(ManagedScope scope, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT request_sequence,checkpoint_sequence FROM ma.model_call_result"
                                + " WHERE scope_key=? AND model_call_id=?",
                        scope.messageGroup(),
                        id);
        return rows.isEmpty()
                ? new RequestPosition(0, -1)
                : new RequestPosition(
                        ((Number) rows.get(0).get("request_sequence")).longValue(),
                        ((Number) rows.get(0).get("checkpoint_sequence")).longValue());
    }

    void register(
            SessionCommit batch,
            List<ManagedCommitPlan.ModelRequest> requests,
            List<ManagedCommitPlan.ModelOutcome> prepared,
            List<ManagedCommitPlan.Finalization> finalizations,
            long entryCheckpointSequence) {
        registerFinalization(batch, finalizations);
        for (var request : requests) {
            String id = request.callId();
            long checkpoint =
                    request.checkpointSequence() == null
                            ? entryCheckpointSequence
                            : request.checkpointSequence();
            int inserted =
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.model_call_result(scope_key,model_call_id,turn_id,request_sequence,checkpoint_sequence)"
                                + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                            batch.scope().messageGroup(),
                            id,
                            batch.turnId(),
                            request.sequence(),
                            checkpoint);
            if (inserted != 1)
                throw new ManagedStateException(
                        "model_call_conflict", "Model call identity was reused");
        }
        for (var value : prepared) {
            var result = value.result();
            if (jdbc.update(
                            "UPDATE ma.model_call_result SET"
                                + " completion_sequence=?,status=?,request_hash=?,result_ref=?,replay_hash=?"
                                + " WHERE scope_key=? AND model_call_id=? AND completion_sequence"
                                + " IS NULL AND request_sequence=? AND turn_id=?",
                            result.completionSequence(),
                            result.status(),
                            result.requestHash(),
                            new String(bytes(value.reference()), StandardCharsets.UTF_8),
                            value.replayHash(),
                            batch.scope().messageGroup(),
                            result.modelCallId(),
                            result.requestSequence(),
                            batch.turnId())
                    != 1)
                throw new ManagedStateException(
                        "model_call_conflict", "Model result identity differs");
        }
    }

    private void registerFinalization(
            SessionCommit batch, List<ManagedCommitPlan.Finalization> finalizations) {
        var scope = batch.scope();
        for (var frame : finalizations) {
            if (frame.kind() == ManagedCommitPlan.FinalizationKind.OPEN) {
                jdbc.update(
                        "INSERT INTO"
                            + " ma.finalization_frame(scope_key,turn_id,frame_id,start_sequence,capture_ref)"
                            + " VALUES(?,?,?,?,?)",
                        scope.messageGroup(),
                        batch.turnId(),
                        frame.frameId(),
                        frame.sequence(),
                        frame.captureReference());
            } else if (frame.kind() == ManagedCommitPlan.FinalizationKind.CLOSE) {
                if (jdbc.update(
                                "UPDATE ma.finalization_frame SET completion_sequence=? WHERE"
                                        + " scope_key=? AND turn_id=? AND frame_id=? AND"
                                        + " completion_sequence IS NULL",
                                frame.sequence(),
                                scope.messageGroup(),
                                batch.turnId(),
                                frame.frameId())
                        != 1)
                    throw new ManagedStateException(
                            "finalization_conflict", "Finalization frame differs");
            } else if (frame.kind() == ManagedCommitPlan.FinalizationKind.MODEL) {
                if (jdbc.update(
                                "INSERT INTO"
                                    + " ma.finalization_model(scope_key,turn_id,frame_id,slot,model_call_id,request_hash,sequence)"
                                    + " SELECT ?,?,?,?,?,?,? FROM ma.finalization_frame WHERE"
                                    + " scope_key=? AND turn_id=? AND frame_id=? AND"
                                    + " completion_sequence IS NULL",
                                scope.messageGroup(),
                                batch.turnId(),
                                frame.frameId(),
                                frame.slot(),
                                frame.modelCallId(),
                                frame.requestHash(),
                                frame.sequence(),
                                scope.messageGroup(),
                                batch.turnId(),
                                frame.frameId())
                        != 1)
                    throw new ManagedStateException(
                            "finalization_conflict", "Model descriptor has no open frame");
            }
        }
    }

    ModelCallResult read(ManagedScope scope, String turn, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " request_sequence,completion_sequence,status,request_hash,result_ref,checkpoint_sequence"
                            + " FROM ma.model_call_result WHERE scope_key=? AND turn_id=? AND"
                            + " model_call_id=?",
                        scope.messageGroup(),
                        turn,
                        id);
        if (rows.isEmpty() || rows.get(0).get("completion_sequence") == null) return null;
        var row = rows.get(0);
        var ref = decode((String) row.get("result_ref"), RuntimeObjectStore.Reference.class);
        if (!scope.messageGroup().equals(ref.scope()) || !"model-result".equals(ref.kind()))
            throw new ManagedStateException("model_result_invalid", "Model result scope differs");
        var result = decode(objects.read(ref), ModelCallResult.class);
        if (!scope.messageGroup().equals(result.scope())
                || !turn.equals(result.turnId())
                || !id.equals(result.modelCallId())
                || result.requestSequence() != ((Number) row.get("request_sequence")).longValue()
                || result.completionSequence()
                        != ((Number) row.get("completion_sequence")).longValue()
                || result.entryCheckpointSequence()
                        != ((Number) row.get("checkpoint_sequence")).longValue()
                || !result.status().equals(row.get("status"))
                || !result.requestHash().equals(row.get("request_hash")))
            throw new ManagedStateException(
                    "model_result_invalid", "Model result metadata differs");
        byte[] request = objects.read(result.request());
        if (!RuntimeObjectStore.sha256(request).equals(result.requestHash()))
            throw new ManagedStateException("model_result_invalid", "Frozen request differs");
        return result;
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode model result", invalid);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        return decode(value.getBytes(StandardCharsets.UTF_8), type);
    }

    private <T> T decode(byte[] value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception invalid) {
            throw new ManagedStateException("model_result_invalid", "Cannot decode model result");
        }
    }
}

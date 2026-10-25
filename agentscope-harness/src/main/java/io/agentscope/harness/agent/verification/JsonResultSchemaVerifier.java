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
package io.agentscope.harness.agent.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.state.TaskVerification.Outcome;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.observation.StoredActionObservation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit output-contract check; never infers a schema from a natural-language criterion. */
public final class JsonResultSchemaVerifier implements DeterministicVerifier {
    private final String schemaJson;
    private final String id;
    private final Schema schema;
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonResultSchemaVerifier(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty())
            throw new IllegalArgumentException("Explicit schema required");
        schemaJson = JsonUtils.getJsonCodec().toJson(schema);
        if (schemaJson.length() > 65536) throw new IllegalArgumentException("Schema too large");
        rejectReferences(schema);
        this.schema =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schemaJson);
        try {
            id =
                    "json-schema-v1:"
                            + HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            schemaJson.getBytes(
                                                                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Verdict verify(StoredActionObservation evidence) {
        var action = evidence.observation();
        var result = evidence.result();
        if (action.status() != ActionObservation.Status.RETURNED
                || result == null
                || (result.getState() != ToolResultState.SUCCESS
                        && result.getState() != ToolResultState.RUNNING)
                || result.isSuspended()
                || !Objects.equals(action.toolCallId(), result.getId())
                || !Objects.equals(action.toolName(), result.getName())
                || result.getOutput().size() != 1
                || !(result.getOutput().get(0) instanceof TextBlock text)) {
            return new Verdict(Outcome.UNKNOWN, "Expected one successful textual JSON result");
        }
        if (result.getExecutionDetails() != null
                && (result.getExecutionDetails().outputTruncated()
                        || result.getExecutionDetails().outcome()
                                != ToolExecutionDetails.Outcome.SUCCEEDED)) {
            return new Verdict(Outcome.UNKNOWN, "Producer outcome is incomplete or unsuccessful");
        }
        if (text.getText() == null || text.getText().length() > 262144) {
            return new Verdict(
                    Outcome.UNKNOWN, "Output is absent or exceeds the bounded schema-check size");
        }
        try {
            if (JSON.readTree(text.getText()) == null)
                return new Verdict(Outcome.FAILED, "JSON output is empty");
        } catch (JsonProcessingException error) {
            return new Verdict(Outcome.FAILED, "Output is not a single valid JSON value");
        }
        return schema.validate(text.getText(), InputFormat.JSON).isEmpty()
                ? new Verdict(
                        Outcome.PASSED, "Selected JSON output conforms to the configured schema")
                : new Verdict(
                        Outcome.FAILED,
                        "Selected output does not conform to the configured JSON schema");
    }

    private static void rejectReferences(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (List.of("$ref", "$dynamicRef", "$recursiveRef", "$schema", "$id")
                        .contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Referenced schemas and dialect overrides are not supported by this"
                                    + " bounded offline verifier");
                }
                rejectReferences(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(JsonResultSchemaVerifier::rejectReferences);
        }
    }
}

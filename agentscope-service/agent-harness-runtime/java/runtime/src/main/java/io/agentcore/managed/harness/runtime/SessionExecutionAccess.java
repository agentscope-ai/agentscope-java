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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Bounded synchronous capability client; callers must run on a worker thread. */
public final class SessionExecutionAccess implements ExecutionAccess {
    @FunctionalInterface
    public interface Transport {
        JsonNode post(String operation, Object request);
    }

    private final Transport transport;
    private final ObjectMapper mapper;
    private final ProtocolSchemas schemas = new ProtocolSchemas();

    public SessionExecutionAccess(SessionServiceClient client, ObjectMapper mapper) {
        this(client::post, mapper);
    }

    public SessionExecutionAccess(Transport transport, ObjectMapper mapper) {
        this.transport = Objects.requireNonNull(transport);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Claim claim(Candidate candidate, Owner owner) {
        var request =
                mapper.createObjectNode()
                        .put("protocolVersion", "0.1.0")
                        .put("turnId", candidate.turnId())
                        .put("harnessType", "agentscope-java")
                        .put("claimId", UUID.randomUUID().toString());
        request.set("scope", mapper.valueToTree(candidate.scope()));
        request.set("owner", mapper.valueToTree(owner));
        request.set("trigger", candidate.trigger().deepCopy());
        schemas.request("executions/claim", request);
        var response = retrySame("executions/claim", request);
        schemas.validate("execution/ClaimReceipt", response);
        if (!response.required("scope").equals(request.required("scope"))
                || !response.required("claimId").equals(request.required("claimId"))
                || !response.required("fence").required("owner").equals(request.required("owner")))
            throw new IllegalStateException("Claim response identity differs");
        // Ready candidates can become stale. Always use the authoritative returned Turn.
        return new Claim(
                candidate.scope(),
                response.required("turnId").asText(),
                mapper.convertValue(response.required("fence"), Fence.class));
    }

    @Override
    public Renewal renew(Claim claim) {
        var request = request(claim).put("renewalId", UUID.randomUUID().toString());
        long before = System.nanoTime();
        var response = retrySame("executions/renew", request);
        schemas.validate("execution/RenewReceipt", response);
        identity(request, response);
        if (!request.required("renewalId").equals(response.required("renewalId"))
                || !request.required("fence").equals(response.required("fence")))
            throw new IllegalStateException("Renewal response identity differs");
        long remaining =
                Duration.between(
                                        Instant.parse(response.required("serverTime").asText()),
                                        Instant.parse(response.required("expiresAt").asText()))
                                .toMillis()
                        - Duration.ofNanos(System.nanoTime() - before).toMillis();
        if (remaining <= 0)
            throw new IllegalStateException("Execution lease expired during renewal");
        return new Renewal(remaining);
    }

    @Override
    public JsonNode inputs(Claim claim) {
        var request = request(claim);
        var response = transport.post("inputs/read", request);
        identity(request, response);
        return response;
    }

    @Override
    public void release(Claim claim) {
        var request = request(claim);
        var response = retrySame("executions/release", request);
        identity(request, response);
        if (!response.path("released").asBoolean())
            throw new IllegalStateException("Execution release was not confirmed");
    }

    public ObjectNode request(Claim claim) {
        var request =
                mapper.createObjectNode()
                        .put("protocolVersion", "0.1.0")
                        .put("turnId", claim.turnId());
        request.set("scope", mapper.valueToTree(claim.scope()));
        request.set("fence", mapper.valueToTree(claim.fence()));
        return request;
    }

    private JsonNode retrySame(String operation, ObjectNode request) {
        schemas.request(operation, request);
        try {
            return transport.post(operation, request);
        } catch (SessionServiceClient.Unavailable uncertain) {
            // No new claim/renewal identity after a lost response. A second uncertainty escapes.
            return transport.post(operation, request);
        }
    }

    private void identity(JsonNode request, JsonNode response) {
        if (!"0.1.0".equals(response.path("protocolVersion").asText())
                || !request.required("scope").equals(response.required("scope"))
                || !request.required("turnId").equals(response.required("turnId")))
            throw new IllegalStateException("Execution response identity differs");
    }
}

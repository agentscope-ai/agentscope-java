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

/** Platform execution capabilities, independent of a native kernel and infrastructure clients. */
public interface ExecutionAccess {
    record Scope(String tenant, String namespace, String sessionId) {}

    record Owner(String instanceId, String incarnationId) {}

    record Candidate(Scope scope, String turnId, JsonNode trigger) {}

    record Fence(Owner owner, String epoch, String executionToken) {}

    record Claim(Scope scope, String turnId, Fence fence) {}

    record Renewal(long remainingMillis) {}

    Claim claim(Candidate candidate, Owner owner);

    Renewal renew(Claim claim);

    JsonNode inputs(Claim claim);

    void release(Claim claim);
}

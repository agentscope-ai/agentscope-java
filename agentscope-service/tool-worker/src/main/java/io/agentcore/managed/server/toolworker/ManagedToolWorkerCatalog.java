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
package io.agentcore.managed.server.toolworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.managed.ManagedExecutionTargets;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;

/** Resolves only the immutable target carried by an admitted command, without a Agent Service/runtime read. */
public final class ManagedToolWorkerCatalog implements ManagedExecutionTargets {
    private final ManagedToolWorkerStore executions;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;

    public ManagedToolWorkerCatalog(
            ManagedToolWorkerStore executions, ObjectMapper mapper, VaultCrypto crypto) {
        this.executions = executions;
        this.mapper = mapper;
        this.crypto = crypto;
    }

    @Override
    public SessionResolveResult resolve(ManagedScope scope) {
        try {
            var node = mapper.readTree(crypto.decrypt(executions.configuration(scope)));
            var value = mapper.treeToValue(node.get("resolved"), SessionResolveResult.class);
            if (!scope.sessionId().equals(value.session().id()))
                throw new IllegalArgumentException("Execution target scope differs");
            return value;
        } catch (Exception invalid) {
            throw new IllegalStateException("Cannot resolve sealed Tool Worker target", invalid);
        }
    }
}

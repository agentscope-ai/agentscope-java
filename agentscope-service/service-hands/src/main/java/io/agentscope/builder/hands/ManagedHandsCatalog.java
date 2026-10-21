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
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.managed.ManagedExecutionTargets;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.web.managed.service.VaultCrypto;

/** Resolves only the immutable target carried by an admitted command, without a Session Portal/runtime read. */
public final class ManagedHandsCatalog implements ManagedExecutionTargets {
    private final ManagedHandsStore executions;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;

    public ManagedHandsCatalog(
            ManagedHandsStore executions, ObjectMapper mapper, VaultCrypto crypto) {
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
            throw new IllegalStateException("Cannot resolve sealed Hands target", invalid);
        }
    }
}

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

import io.agentcore.managed.harness.agentscope.AgentScopeEventSchemas;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;

/** Service-only backend. Opening a reader neither claims an epoch nor acquires a Redis token. */
public final class ManagedSessionLogStore implements SessionLogStore {
    private final ManagedJournalStore journal;

    public ManagedSessionLogStore(ManagedJournalStore journal) {
        AgentScopeEventSchemas.ensureRegistered();
        this.journal = journal;
    }

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        if (context == null)
            throw new IllegalArgumentException("Managed storage requires scoped RuntimeContext");
        var execution = context.get(ManagedTurnExecution.class);
        if (execution != null) {
            if (!key.sessionId().equals(execution.scope().sessionId()))
                throw new IllegalArgumentException(
                        "Native SessionKey differs from active execution");
            return execution.log();
        }
        var scope = context.get(ManagedScope.class);
        if (scope == null || !key.sessionId().equals(scope.sessionId()))
            throw new IllegalArgumentException("Managed reader requires explicit Session scope");
        journal.head(scope);
        return new ManagedMqSessionLog(scope, null, 0, null, journal, null);
    }
}

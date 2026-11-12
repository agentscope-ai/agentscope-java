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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-local accounting changes reuse the already fenced execution aggregate. */
final class ManagedExecutionChanges {
    final ManagedScope scope;
    final String turn;
    final ManagedTurnChanges turnChanges;
    final String originalUsage;
    String usage;

    private ManagedExecutionChanges(
            ManagedScope scope,
            String turn,
            Map<String, Object> session,
            ManagedTurnChanges changes) {
        this.scope = scope;
        this.turn = turn;
        this.turnChanges = changes;
        originalUsage = (String) session.get("usage_summary");
        usage = originalUsage;
    }

    static ManagedExecutionChanges begin(
            DataSource source,
            ManagedScope scope,
            String turn,
            Map<String, Object> session,
            ManagedTurnChanges changes) {
        var key = List.of(ManagedExecutionChanges.class, source);
        var value = new ManagedExecutionChanges(scope, turn, session, changes);
        TransactionSynchronizationManager.bindResource(key, value);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(key);
                    }

                    @Override
                    public void suspend() {
                        TransactionSynchronizationManager.unbindResourceIfPossible(key);
                    }

                    @Override
                    public void resume() {
                        TransactionSynchronizationManager.bindResource(key, value);
                    }
                });
        return value;
    }

    static ManagedExecutionChanges current(DataSource source, ManagedScope scope) {
        var value =
                (ManagedExecutionChanges)
                        TransactionSynchronizationManager.getResource(
                                List.of(ManagedExecutionChanges.class, source));
        return value != null && value.scope.equals(scope) ? value : null;
    }

    boolean usageChanged() {
        return !Objects.equals(originalUsage, usage);
    }
}

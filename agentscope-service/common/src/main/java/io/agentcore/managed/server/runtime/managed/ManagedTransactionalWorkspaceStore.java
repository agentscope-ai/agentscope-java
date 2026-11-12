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

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Marks a workspace store whose JDBC calls enlist through TransactionAwareDataSourceProxy. */
public final class ManagedTransactionalWorkspaceStore implements BaseStore {
    private final BaseStore delegate;
    private final DataSource source;

    public ManagedTransactionalWorkspaceStore(BaseStore delegate, DataSource source) {
        this.delegate = delegate;
        this.source = source;
    }

    boolean participatesIn(ManagedJournalStore journal) {
        return journal.workspaceDataSource(source);
    }

    public boolean supportsAtomicSessionStorage() {
        return delegate.supportsAtomicSessionStorage();
    }

    public StoreItem get(List<String> namespace, String key) {
        return delegate.get(namespace, key);
    }

    public void put(List<String> namespace, String key, Map<String, Object> value) {
        delegate.put(namespace, key, value);
    }

    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long version) {
        if (version != 0) return delegate.putIfVersion(namespace, key, value, version);
        // JdbcStore catches duplicate-key SQL errors; PostgreSQL still aborts that transaction.
        // Isolate the expected insert conflict in a savepoint before recording its false result.
        var nested = new TransactionTemplate(new DataSourceTransactionManager(source));
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return Boolean.TRUE.equals(
                nested.execute(
                        status -> {
                            boolean written = delegate.putIfVersion(namespace, key, value, version);
                            if (!written) status.setRollbackOnly();
                            return written;
                        }));
    }

    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        return delegate.search(namespace, limit, offset);
    }

    public void delete(List<String> namespace, String key) {
        delegate.delete(namespace, key);
    }
}

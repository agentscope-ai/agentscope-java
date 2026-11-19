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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.util.Objects;

/**
 * One-line wiring for a shared {@link BaseStore}: hands out the three protocol collaborators that a
 * multi-instance deployment must share, all backed by the store passed in.
 *
 * <p>Declaring a single bean is enough — the auto-configuration uses it instead of the process-local
 * defaults, so submission, {@code /resume}, {@code /cancel} and the {@code /wait} poll can be served
 * by any instance:
 *
 * <pre>{@code
 * @Bean
 * ProtocolStores protocolStores(BaseStore protocolStore) {
 *     return ProtocolStores.on(protocolStore);
 * }
 * }</pre>
 *
 * <p>Each collaborator keeps its own namespace under {@code agent-protocol}, so pointing several
 * implementations at one store never makes them share keys.
 *
 * <p>Pass the protocol control plane's own store instance. A store that is scoped by an agent
 * identifier — as a per-agent store usually is — partitions rows by whichever agent created the
 * instance; construct it with a dedicated identity (for example the protocol control-plane agent id)
 * so protocol state does not land under an execution agent's partition.
 *
 * <p>The run registry needs atomic compare-and-set: it fences acquisitions and renewals with
 * {@link BaseStore#putIfVersion}. A store without it falls back to a write-then-read-back that
 * detects a lost race but cannot prevent two instances from acquiring in the same window. {@link
 * #on(BaseStore)} therefore refuses such a store instead of silently wiring an unsafe shared
 * registry. Object-storage implementations (OSS, COS) report no atomic updates and cannot be used
 * here; use Redis, JDBC, MySQL, PostgreSQL or MongoDB. A single-instance deployment needs no bean
 * at all — the auto-configuration supplies process-local in-memory collaborators.
 */
public final class ProtocolStores {

    private final ProtocolTaskRunRegistry runRegistry;
    private final ProtocolTaskContextStore contextStore;
    private final ProtocolSessionRegistry sessionRegistry;

    private ProtocolStores(BaseStore store) {
        this.runRegistry = new BaseStoreProtocolTaskRunRegistry(store);
        this.contextStore = new BaseStoreProtocolTaskContextStore(store);
        this.sessionRegistry = new BaseStoreProtocolSessionRegistry(store);
    }

    /**
     * Wires all three collaborators to {@code store}.
     *
     * <p>Run leases record the holder as {@code <host>:<pid>:<random>}, derived per instance — a
     * lease read from the store therefore names the machine running it without any configuration.
     *
     * @throws IllegalStateException when {@code store} cannot fence acquisitions with
     *     compare-and-set ({@link BaseStore#supportsAtomicJournalStorage()} is {@code false})
     */
    public static ProtocolStores on(BaseStore store) {
        Objects.requireNonNull(store, "store");
        if (!store.supportsAtomicJournalStorage()) {
            throw new IllegalStateException(
                    "ProtocolStores requires a BaseStore with atomic compare-and-set; "
                            + store.getClass().getName()
                            + " cannot fence concurrent lease acquisitions, so two instances could"
                            + " own the same task. Use Redis, JDBC, MySQL, PostgreSQL or MongoDB,"
                            + " or omit this bean for a single-instance deployment.");
        }
        return new ProtocolStores(store);
    }

    /** Run leases and cancel intents; the same instance on every call. */
    public ProtocolTaskRunRegistry runRegistry() {
        return runRegistry;
    }

    /** Submission metadata re-read by {@code /resume}; the same instance on every call. */
    public ProtocolTaskContextStore contextStore() {
        return contextStore;
    }

    /** Session ownership records; the same instance on every call. */
    public ProtocolSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }
}

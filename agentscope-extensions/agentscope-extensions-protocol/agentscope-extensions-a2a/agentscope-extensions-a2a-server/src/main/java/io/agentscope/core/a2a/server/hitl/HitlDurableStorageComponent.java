/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Verifiable identity shared by durable A2A TaskStore, coordinator and lease components. */
public interface HitlDurableStorageComponent {

    /** The exact provider client object used for storage operations. */
    Object storageClientIdentity();

    /** Stable identifier of the logical backend selected by application configuration. */
    String logicalStoreId();

    /** Component namespace or key prefix within that backend. */
    String namespace();
}

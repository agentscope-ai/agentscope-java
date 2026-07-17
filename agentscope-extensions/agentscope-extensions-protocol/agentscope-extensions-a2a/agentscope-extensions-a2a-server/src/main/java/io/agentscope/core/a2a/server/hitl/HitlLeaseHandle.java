/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Owned session lease. Closing is idempotent and releases only this owner's lease. */
public interface HitlLeaseHandle extends AutoCloseable {

    /** Register an action that aborts execution if ownership is lost during renewal. */
    default void onLost(Runnable action) {}

    /** Whether this handle has retained ownership for its lifetime. */
    default boolean isValid() {
        return true;
    }

    @Override
    void close();
}

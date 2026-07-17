/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Resume persistence capability explicitly declared by a runner or store implementation. */
public enum HitlDurabilityCapability {
    UNSUPPORTED,
    LOCAL,
    DURABLE
}

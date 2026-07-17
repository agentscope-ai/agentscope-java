/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Safe-to-log control-plane coordinates returned after durable wiring verification. */
public record HitlDurabilityVerification(String coordinationProvider, String coordinationStoreId) {

    public HitlDurabilityVerification {
        coordinationProvider = requireText(coordinationProvider, "coordinationProvider");
        coordinationStoreId = requireText(coordinationStoreId, "coordinationStoreId");
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

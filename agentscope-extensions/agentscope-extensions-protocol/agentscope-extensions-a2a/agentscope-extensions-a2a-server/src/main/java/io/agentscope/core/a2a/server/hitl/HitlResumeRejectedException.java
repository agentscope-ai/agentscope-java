/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

/** Safe rejection that never includes credential material. */
public class HitlResumeRejectedException extends IllegalStateException {

    public HitlResumeRejectedException(String message) {
        super(message);
    }
}

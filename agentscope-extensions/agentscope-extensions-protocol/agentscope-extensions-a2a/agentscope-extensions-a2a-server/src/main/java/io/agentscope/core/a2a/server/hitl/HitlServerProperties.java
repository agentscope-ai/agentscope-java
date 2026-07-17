/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.time.Duration;

/** Server-side HITL behavior independent of any persistence technology. */
public final class HitlServerProperties {

    public enum Durability {
        LOCAL,
        DURABLE
    }

    private final boolean enabled;
    private final Durability durability;
    private final Duration taskTtl;
    private final Duration handoffTtl;

    private final Duration executionLeaseTtl;

    private HitlServerProperties(
            boolean enabled,
            Durability durability,
            Duration taskTtl,
            Duration handoffTtl,
            Duration executionLeaseTtl) {
        this.enabled = enabled;
        this.durability = durability;
        this.taskTtl = taskTtl;
        this.handoffTtl = handoffTtl;
        this.executionLeaseTtl = executionLeaseTtl;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean enabled() {
        return enabled;
    }

    public Durability durability() {
        return durability;
    }

    public Duration taskTtl() {
        return taskTtl;
    }

    public Duration handoffTtl() {
        return handoffTtl;
    }

    public Duration executionLeaseTtl() {
        return executionLeaseTtl;
    }

    public static final class Builder {
        private boolean enabled;
        private Durability durability = Durability.LOCAL;
        private Duration taskTtl = Duration.ofDays(30);
        private Duration handoffTtl = Duration.ofDays(7);
        private Duration executionLeaseTtl = Duration.ofMinutes(1);

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder durability(Durability durability) {
            this.durability = durability;
            return this;
        }

        public Builder taskTtl(Duration taskTtl) {
            this.taskTtl = taskTtl;
            return this;
        }

        public Builder handoffTtl(Duration handoffTtl) {
            this.handoffTtl = handoffTtl;
            return this;
        }

        public Builder executionLeaseTtl(Duration executionLeaseTtl) {
            this.executionLeaseTtl = executionLeaseTtl;
            return this;
        }

        public HitlServerProperties build() {
            return new HitlServerProperties(
                    enabled,
                    durability == null ? Durability.LOCAL : durability,
                    taskTtl == null ? Duration.ofDays(30) : taskTtl,
                    handoffTtl == null ? Duration.ofDays(7) : handoffTtl,
                    executionLeaseTtl == null ? Duration.ofMinutes(1) : executionLeaseTtl);
        }
    }
}

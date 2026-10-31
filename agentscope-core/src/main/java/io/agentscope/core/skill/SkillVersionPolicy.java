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
package io.agentscope.core.skill;

import java.util.Objects;

/**
 * Declares how the versions of the skills an agent loads are bound: {@link Mode#LATEST}
 * (the default) serves each repository's current content, while {@link Mode#PIN} (opt-in)
 * binds the version resolved at session start and restores it on resume.
 *
 * <p>One policy per agent, declared on the builder via {@code skillVersionPolicy(policy)};
 * an undeclared policy behaves as {@link #latest()}. When a pinned version cannot be served,
 * {@link OnUnreachable} decides between failing the load and falling back.
 *
 * <p>Example usage:
 * <pre>{@code
 * skillVersionPolicy(SkillVersionPolicy.pinned())                     // pin, default fallback
 * skillVersionPolicy(SkillVersionPolicy.pinned().failOnUnreachable()) // strict pin
 * skillVersionPolicy(SkillVersionPolicy.latest())                     // default (implicit)
 * }</pre>
 */
public final class SkillVersionPolicy {

    /** How the skill's version is bound. */
    public enum Mode {
        /** Serve the repository's current content on every call (the default). */
        LATEST,
        /** Bind the version resolved at session start and restore it on resume (opt-in). */
        PIN
    }

    /**
     * What happens when a pinned version cannot be served at load time. Only meaningful for
     * {@link Mode#PIN}; a pin that no registered repository can serve at all is surfaced the
     * same way when the session is established.
     */
    public enum OnUnreachable {
        /**
         * Fail the load — the broken pin surfaces as an error instead of a version swap.
         * Explicit opt-in via {@code failOnUnreachable()}.
         */
        ERROR,
        /**
         * Pull the current latest once, re-anchor the session record to the version actually
         * served, log the fallback at error level, then continue under pin semantics (the
         * default).
         */
        FALLBACK_TO_LATEST
    }

    private final Mode mode;
    private final OnUnreachable onUnreachable;

    /** Creates a policy; use the static factories or the builder. */
    private SkillVersionPolicy(Mode mode, OnUnreachable onUnreachable) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.onUnreachable =
                Objects.requireNonNull(onUnreachable, "onUnreachable must not be null");
    }

    /**
     * Returns the default policy: {@link Mode#LATEST} with {@link
     * OnUnreachable#FALLBACK_TO_LATEST} (the unreachable decision only matters for pin).
     */
    public static SkillVersionPolicy latest() {
        return new SkillVersionPolicy(Mode.LATEST, OnUnreachable.FALLBACK_TO_LATEST);
    }

    /**
     * Returns a pin policy with the default unreachable decision ({@link
     * OnUnreachable#FALLBACK_TO_LATEST}): an unreachable version pulls the current latest
     * once and re-anchors the session record. Use {@link #failOnUnreachable()} when a broken
     * pin must fail the load instead.
     */
    public static SkillVersionPolicy pinned() {
        return new SkillVersionPolicy(Mode.PIN, OnUnreachable.FALLBACK_TO_LATEST);
    }

    /**
     * Returns a copy of this policy with {@link OnUnreachable#FALLBACK_TO_LATEST} — the
     * default decision, where an unreachable version falls back to the current latest once.
     *
     * @return a policy equal to this one except for the unreachable decision
     */
    public SkillVersionPolicy fallbackToLatest() {
        return new SkillVersionPolicy(mode, OnUnreachable.FALLBACK_TO_LATEST);
    }

    /**
     * Returns a copy of this policy with {@link OnUnreachable#ERROR}: an unreachable version
     * fails the load instead of being swapped for the latest.
     *
     * @return a policy equal to this one except for the unreachable decision
     */
    public SkillVersionPolicy failOnUnreachable() {
        return new SkillVersionPolicy(mode, OnUnreachable.ERROR);
    }

    /**
     * Returns a builder initialized with the defaults ({@link Mode#LATEST}, {@link
     * OnUnreachable#FALLBACK_TO_LATEST}).
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns how this policy binds the skill's version. */
    public Mode mode() {
        return mode;
    }

    /** Returns the decision for when a pinned version cannot be served. */
    public OnUnreachable onUnreachable() {
        return onUnreachable;
    }

    /** Value equality: two policies are equal when both fields match. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillVersionPolicy that)) {
            return false;
        }
        return mode == that.mode && onUnreachable == that.onUnreachable;
    }

    /** Hash code over both fields, consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(mode, onUnreachable);
    }

    /** Debug representation showing both fields. */
    @Override
    public String toString() {
        return "SkillVersionPolicy{mode=" + mode + ", onUnreachable=" + onUnreachable + "}";
    }

    /** Builder for {@link SkillVersionPolicy}; defaults match {@link #latest()}. */
    public static final class Builder {

        private Mode mode = Mode.LATEST;
        private OnUnreachable onUnreachable = OnUnreachable.FALLBACK_TO_LATEST;

        /** Creates a builder initialized with the defaults. */
        private Builder() {}

        /**
         * Sets how the skill's version is bound.
         *
         * @param mode the binding mode (never null)
         * @return this builder
         */
        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode must not be null");
            return this;
        }

        /**
         * Sets what happens when a pinned version cannot be served; only meaningful for
         * {@link Mode#PIN}.
         *
         * @param onUnreachable the unreachable-version decision (never null)
         * @return this builder
         */
        public Builder onUnreachable(OnUnreachable onUnreachable) {
            this.onUnreachable =
                    Objects.requireNonNull(onUnreachable, "onUnreachable must not be null");
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return a new immutable policy
         */
        public SkillVersionPolicy build() {
            return new SkillVersionPolicy(mode, onUnreachable);
        }
    }
}

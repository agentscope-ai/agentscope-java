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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable filter that decides which skills are allowed in a prompt.
 *
 * <p>Two usage patterns:
 * <ul>
 *   <li><b>Standalone</b> (builder level) — {@link #all()}, {@link #none()}, {@link #only},
 *       {@link #except} express a complete policy.</li>
 *   <li><b>Overlay</b> (RuntimeContext level) — {@link #enable}, {@link #disable} express
 *       partial overrides that are merged with a base filter via {@link #overlay}.</li>
 * </ul>
 *
 * <p>When no {@code SkillFilter} is configured, the default behaviour is {@link #all()} —
 * every skill is enabled.
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * // Builder level: only allow two skills
 * SkillFilter base = SkillFilter.only("skill_a", "skill_b");
 *
 * // Runtime: disable one of them for this call
 * SkillFilter runtime = SkillFilter.disable("skill_b");
 *
 * // Merge: skill_a enabled, skill_b disabled
 * SkillFilter effective = base.overlay(runtime);
 * }</pre>
 */
public class SkillFilter {

    private enum Mode {
        ALL,
        NONE,
        WHITELIST,
        BLACKLIST,
        OVERLAY_ENABLE,
        OVERLAY_DISABLE
    }

    private final Mode mode;
    private final Set<String> skillNames;

    private SkillFilter(Mode mode, Set<String> skillNames) {
        this.mode = mode;
        this.skillNames =
                skillNames != null
                        ? Collections.unmodifiableSet(new HashSet<>(skillNames))
                        : Set.of();
    }

    // ==================== Standalone factory methods ====================

    /** All skills enabled (default when no filter is configured). */
    public static SkillFilter all() {
        return new SkillFilter(Mode.ALL, null);
    }

    /** All skills disabled. */
    public static SkillFilter none() {
        return new SkillFilter(Mode.NONE, null);
    }

    /** Whitelist: only the named skills are allowed. */
    public static SkillFilter only(String... skillNames) {
        return new SkillFilter(Mode.WHITELIST, Set.of(skillNames));
    }

    /** Blacklist: all skills except the named ones are allowed. */
    public static SkillFilter except(String... skillNames) {
        return new SkillFilter(Mode.BLACKLIST, Set.of(skillNames));
    }

    // ==================== Overlay factory methods ====================

    /** Overlay: explicitly enable these skills (others unchanged). */
    public static SkillFilter enable(String... skillNames) {
        return new SkillFilter(Mode.OVERLAY_ENABLE, Set.of(skillNames));
    }

    /** Overlay: explicitly disable these skills (others unchanged). */
    public static SkillFilter disable(String... skillNames) {
        return new SkillFilter(Mode.OVERLAY_DISABLE, Set.of(skillNames));
    }

    // ==================== Query ====================

    /**
     * Whether the given skill is allowed by this filter.
     *
     * @param skillName the skill name to check
     * @return true if allowed
     */
    public boolean isAllowed(String skillName) {
        return switch (mode) {
            case ALL -> true;
            case NONE -> false;
            case WHITELIST -> skillNames.contains(skillName);
            case BLACKLIST -> !skillNames.contains(skillName);
            case OVERLAY_ENABLE -> skillNames.contains(skillName);
            case OVERLAY_DISABLE -> !skillNames.contains(skillName);
        };
    }

    /**
     * Whether this filter is an overlay (partial override) rather than a standalone filter.
     *
     * @return true if this is an overlay filter
     */
    public boolean isOverlay() {
        return mode == Mode.OVERLAY_ENABLE || mode == Mode.OVERLAY_DISABLE;
    }

    // ==================== Merge ====================

    /**
     * Merge this (base) filter with a runtime overlay.
     *
     * <p>Skills explicitly mentioned in the overlay use the overlay's decision.
     * Skills not mentioned in the overlay fall through to this base filter.
     *
     * <p>If {@code runtimeOverlay} is null or is not an overlay filter, it replaces
     * this filter entirely.
     *
     * @param runtimeOverlay the per-call override (may be null)
     * @return the effective filter
     */
    public SkillFilter overlay(SkillFilter runtimeOverlay) {
        if (runtimeOverlay == null) {
            return this;
        }
        if (!runtimeOverlay.isOverlay()) {
            return runtimeOverlay;
        }
        return merged(this, runtimeOverlay);
    }

    private static SkillFilter merged(SkillFilter base, SkillFilter overlay) {
        return new SkillFilter(Mode.ALL, null) {
            @Override
            public boolean isAllowed(String skillName) {
                if (overlay.skillNames.contains(skillName)) {
                    return overlay.isAllowed(skillName);
                }
                return base.isAllowed(skillName);
            }

            @Override
            public boolean isOverlay() {
                return false;
            }
        };
    }
}

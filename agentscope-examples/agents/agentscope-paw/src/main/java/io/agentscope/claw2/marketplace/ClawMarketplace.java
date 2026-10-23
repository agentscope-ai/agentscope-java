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
package io.agentscope.claw2.marketplace;

import java.util.List;

/**
 * A claw-managed skill marketplace. Browsed and configured via the UI, independent of the
 * runtime {@code skillRepositories} that an agent itself loads.
 *
 * <p>Implementations are stateful (open git clones, open nacos clients) and must be closed
 * when the registry replaces or removes them.
 */
public interface ClawMarketplace extends AutoCloseable {

    /** Stable id, chosen by the user when the marketplace was created. */
    String id();

    /** Discriminator used by the UI for badges and config forms ({@code "git"} / {@code "nacos"}). */
    String type();

    /** Human-readable location shown in the UI (URL, server address, etc.). Never includes credentials. */
    String displayLocation();

    /** Whether this marketplace would accept writes — claw is read-only today, but UI may surface this. */
    default boolean writable() {
        return false;
    }

    /**
     * List all skills exposed by this marketplace. Implementations may cache or hit the upstream
     * on every call; callers should treat this as potentially slow and let the UI lazy-load.
     */
    List<MarketSkillSummary> list();

    /**
     * Fetch the full content (SKILL.md plus any side resources) for the named skill, or
     * {@code null} if it does not exist.
     */
    MarketSkillContent fetch(String name);

    /** Release upstream resources (close git, shut down nacos client). Safe to call repeatedly. */
    @Override
    void close();
}

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
package io.agentscope.harness.agent.team;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridge from {@link TeamClient} implementations to whatever runtime owns teammate sessions.
 *
 * <p>A {@link TeamClient} knows member names ({@code lead}, {@code w1}) but not runtime session ids,
 * so it cannot wake a teammate on its own. The middleware that hosts the teammate registers a
 * {@link Hook} here; clients then notify members without depending on the middleware package.
 */
public final class TeamWakeups {

    /** Wakes a teammate, optionally injecting {@code notice} into its next turn. */
    @FunctionalInterface
    public interface Hook {
        boolean wake(String teamName, String memberName, String notice);
    }

    private static final AtomicReference<Hook> HOOK = new AtomicReference<>();

    private TeamWakeups() {}

    /** Registers the process-wide wakeup hook. */
    public static void register(Hook hook) {
        HOOK.set(hook);
    }

    /** Wakes {@code memberName}; returns {@code false} when no hook or no live session. */
    public static boolean wake(String teamName, String memberName, String notice) {
        Hook hook = HOOK.get();
        if (hook == null || teamName == null || memberName == null || memberName.isBlank()) {
            return false;
        }
        try {
            return hook.wake(teamName, memberName, notice);
        } catch (RuntimeException e) {
            return false;
        }
    }
}

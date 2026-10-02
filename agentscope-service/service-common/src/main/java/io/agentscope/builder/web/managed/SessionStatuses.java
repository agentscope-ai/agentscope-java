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
package io.agentscope.builder.web.managed;

/**
 * Shared session status vocabulary.
 *
 * <p>Ownership across planes:
 *
 * <ul>
 *   <li>Lifecycle states ({@link #CREATED}, {@link #ARCHIVED}, {@link #TERMINATED}) — control plane
 *   <li>Runtime states ({@link #RUNNING}, {@link #IDLE}, {@link #REQUIRES_ACTION}, {@link
 *       #RESCHEDULED}) — data plane
 * </ul>
 */
public final class SessionStatuses {

    private SessionStatuses() {}

    public static final String CREATED = "created";
    public static final String RUNNING = "running";
    public static final String IDLE = "idle";
    public static final String REQUIRES_ACTION = "requires_action";
    public static final String TERMINATED = "terminated";
    public static final String RESCHEDULED = "rescheduled";
    public static final String ARCHIVED = "archived";

    /** Runtime statuses that only the data plane should write. */
    public static boolean isRuntimeStatus(String status) {
        return RUNNING.equals(status)
                || IDLE.equals(status)
                || REQUIRES_ACTION.equals(status)
                || RESCHEDULED.equals(status);
    }

    /** Lifecycle statuses that only the control plane should write. */
    public static boolean isLifecycleStatus(String status) {
        return CREATED.equals(status) || ARCHIVED.equals(status) || TERMINATED.equals(status);
    }
}

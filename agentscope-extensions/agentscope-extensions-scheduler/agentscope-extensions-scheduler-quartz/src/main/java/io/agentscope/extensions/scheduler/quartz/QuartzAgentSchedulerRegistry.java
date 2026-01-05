/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.scheduler.quartz;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for {@link QuartzAgentScheduler} instances.
 *
 * <p>This registry allows {@link AgentQuartzJob}s to locate the scheduler instance
 * that created them, which is necessary for retrieving task information and
 * executing agent logic.
 */
class QuartzAgentSchedulerRegistry {

    private static final Map<String, QuartzAgentScheduler> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Register a QuartzAgentScheduler instance with the given ID.
     *
     * @param id       The ID to associate with the scheduler
     * @param scheduler The QuartzAgentScheduler instance to register
     */
    static void register(String id, QuartzAgentScheduler scheduler) {
        REGISTRY.put(id, scheduler);
    }

    /**
     * Unregister the QuartzAgentScheduler instance associated with the given ID.
     *
     * @param id The ID of the scheduler to unregister
     */
    static void unregister(String id) {
        REGISTRY.remove(id);
    }

    /**
     * Get the QuartzAgentScheduler instance associated with the given ID.
     *
     * @param id The ID of the scheduler to retrieve
     * @return The QuartzAgentScheduler instance, or null if no such scheduler exists
     */
    static QuartzAgentScheduler get(String id) {
        return REGISTRY.get(id);
    }
}

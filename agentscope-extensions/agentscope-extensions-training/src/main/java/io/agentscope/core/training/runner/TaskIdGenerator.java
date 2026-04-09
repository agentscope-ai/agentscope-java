/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.runner;

import java.util.UUID;

/**
 * Task ID Generator
 *
 * <p>Automatically generates unique Task ID for each Agent call.
 *
 * <p>Task ID is used to identify a user's complete request and is the top-level identifier for training data tracking.
 *
 * <p><b>Design notes:</b>
 * <ul>
 *   <li>Internal component, not visible to users</li>
 *   <li>Automatically generates new Task ID for each Agent call</li>
 *   <li>Uses UUID to ensure global uniqueness</li>
 *   <li>Prefix "task-" for easy identification</li>
 * </ul>
 *
 * @see RunExecutionContext
 * @see RunRegistry
 */
class TaskIdGenerator {

    private static final String PREFIX = "task";

    // Private constructor to prevent instantiation
    private TaskIdGenerator() {}

    /**
     * Generate unique Task ID
     *
     * <p>Generation strategy: {@code task-{uuid}}
     *
     * <p>Example: {@code task-3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b}
     *
     * @return Unique Task ID
     */
    public static String generate() {
        return PREFIX + "-" + UUID.randomUUID().toString();
    }

    /**
     * Generate Task ID with timestamp (alternative approach)
     *
     * <p>Generation strategy: {@code task-{timestamp}-{random}}
     *
     * <p>Advantages:
     * <ul>
     *   <li>Can be sorted by time</li>
     *   <li>Easy to track task time distribution</li>
     * </ul>
     *
     * <p>Example: {@code task-1704067200000-3e4f5a6b}
     *
     * @return Task ID with timestamp
     */
    public static String generateWithTimestamp() {
        long timestamp = System.currentTimeMillis();
        String randomPart = UUID.randomUUID().toString().substring(0, 8);
        return PREFIX + "-" + timestamp + "-" + randomPart;
    }
}

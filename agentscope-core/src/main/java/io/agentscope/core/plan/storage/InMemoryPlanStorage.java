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
package io.agentscope.core.plan.storage;

import io.agentscope.core.plan.model.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of PlanStorage.
 *
 * <p>Stores plans in a concurrent hash map for thread-safe access. This implementation is suitable
 * for development and testing. For production use cases requiring persistence, implement a
 * database-backed storage.
 */
public class InMemoryPlanStorage implements PlanStorage {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    /**
     * Adds a plan to the storage.
     *
     * <p>If a plan with the same ID already exists, it will be replaced.
     *
     * @param plan The plan to store
     * @return A Mono that completes when the plan is stored
     */
    @Override
    public Mono<Void> addPlan(Plan plan) {
        return Mono.fromRunnable(() -> plans.put(plan.getId(), plan));
    }

    /**
     * Retrieves a plan by its ID.
     *
     * @param planId The unique identifier of the plan
     * @return A Mono emitting the plan if found, or empty if not found
     */
    @Override
    public Mono<Plan> getPlan(String planId) {
        return Mono.justOrEmpty(plans.get(planId));
    }

    /**
     * Retrieves all stored plans.
     *
     * @return A Mono emitting a list of all plans (may be empty)
     */
    @Override
    public Mono<List<Plan>> getPlans() {
        return Mono.just(new ArrayList<>(plans.values()));
    }
}

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
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Storage interface for persisting and retrieving plans.
 *
 * <p>Implementations can store plans in memory, database, or any other persistent storage.
 */
public interface PlanStorage {

    /**
     * Add a plan to storage.
     *
     * @param plan The plan to store
     * @return Mono that completes when the plan is stored
     */
    Mono<Void> addPlan(Plan plan);

    /**
     * Get a plan by its ID.
     *
     * @param planId The plan ID
     * @return Mono emitting the plan, or empty if not found
     */
    Mono<Plan> getPlan(String planId);

    /**
     * Get all plans from storage.
     *
     * @return Mono emitting a list of all plans
     */
    Mono<List<Plan>> getPlans();
}

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

package io.agentscope.core.training.reward;

import io.agentscope.core.agent.Agent;

/**
 * Reward Calculator Interface
 *
 * <p>Calculate reward value based on shadow Agent's execution results
 */
public interface RewardCalculator {

    /**
     * Calculate reward value based on execution results
     *
     * @param agent Shadow Agent
     * @return Reward value (typically between -1 and 1)
     */
    double calculate(Agent agent);
}

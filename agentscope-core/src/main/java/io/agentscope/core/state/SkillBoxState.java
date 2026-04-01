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
package io.agentscope.core.state;

import java.util.Map;

/**
 * State record for SkillBox activation states.
 *
 * <p>This record captures the runtime activation state of all registered skills.
 * The registered skills themselves (AgentSkill objects) are not persisted,
 * as they are configured at agent setup time. Only runtime activation states are saved.
 *
 * @param skillActivationStates Map of skill ID to activation state (true = active)
 */
public record SkillBoxState(Map<String, Boolean> skillActivationStates) implements State {}

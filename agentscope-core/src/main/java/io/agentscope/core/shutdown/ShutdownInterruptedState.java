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
package io.agentscope.core.shutdown;

import io.agentscope.core.state.State;

/**
 * Persistent flag indicating that an agent was interrupted by graceful shutdown.
 *
 * <p>Stored in the {@link io.agentscope.core.session.Session} alongside agent state so
 * the framework can detect a shutdown-interrupted session on the next request and avoid
 * adding a duplicate user prompt.
 */
public record ShutdownInterruptedState(boolean interrupted) implements State {}

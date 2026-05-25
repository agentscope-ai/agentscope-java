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

/**
 * Persistence boundary for {@link io.agentscope.core.state.AgentState}.
 *
 * <p>{@link io.agentscope.core.storage.StorageBase} defines the two operations every backend must
 * implement: load and save per (sessionId, agentId) pair. {@link
 * io.agentscope.core.storage.InMemoryStorage} is the default in-process implementation.
 */
package io.agentscope.core.storage;

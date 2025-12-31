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
package io.agentscope.core.state;

/**
 * Marker interface for persistable state objects.
 *
 * <p>Classes implementing this interface can be serialized and stored by {@link
 * io.agentscope.core.session.Session} implementations. The recommended approach is to use Java
 * Records for simple state objects.
 *
 * <p>Existing domain objects (like {@link io.agentscope.core.message.Msg}) can implement this
 * interface directly to avoid conversion overhead.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Using a Record
 * public record AgentMetaState(
 *     String id,
 *     String name,
 *     String description
 * ) implements State {}
 *
 * // Existing class implementing State
 * public class Msg implements State {
 *     // existing fields and methods
 * }
 * }</pre>
 *
 * @see StateModule
 * @see io.agentscope.core.session.Session
 */
public interface State {}

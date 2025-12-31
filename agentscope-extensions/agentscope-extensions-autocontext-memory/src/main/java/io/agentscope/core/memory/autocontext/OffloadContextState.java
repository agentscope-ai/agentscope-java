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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Map;

/**
 * State record for persisting AutoContextMemory's offload context.
 *
 * <p>When messages are compressed with offload hints (e.g., "Use context_offload_tool to reload
 * full content with uuid: xxx"), the original full content is stored in the offloadContext map.
 * This state record wraps that map for session persistence.
 *
 * <p>Without persisting this state, after loading a session, the agent cannot reload offloaded
 * content because the UUIDs reference non-existent data.
 *
 * @param offloadContext map from UUID to the original messages that were offloaded
 */
public record OffloadContextState(Map<String, List<Msg>> offloadContext) implements State {}

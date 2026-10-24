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
package io.agentscope.extensions.a2ui.envelope;

/** Protocol constants and tool names for the A2UI envelope contract. */
public final class A2uiConstants {

    public static final String PROTOCOL_VERSION = "1.0";
    public static final String MESSAGE_TYPE_CREATE_SURFACE = "createSurface";
    public static final String MESSAGE_TYPE_UPDATE_COMPONENTS = "updateComponents";

    /** The hosting agent's single UI entry (natural-language intent in, envelope out). */
    public static final String TOOL_RENDER = "a2ui_render";

    /** Component-tree submit tool, bound ONLY to the render sub-agent's private toolkit. */
    public static final String TOOL_PRESENT = "a2ui_present";

    /** Catalog read tool, bound ONLY to the render sub-agent (see {@code catalogInPrompt}). */
    public static final String TOOL_CATALOG = "a2ui_catalog";

    /** HITL ask tool — no {@code a2ui_} prefix: plain mode (ClarificationMiddleware) is A2UI-free. */
    public static final String TOOL_ASK_USER_QUESTION = "ask_user_question";

    /** {@code AgentStateStore} key under which session surfaces are persisted. */
    public static final String STATE_STORE_KEY_SURFACES = "a2ui_surfaces";

    /** RuntimeContext key caching the surface id created during a single run. */
    public static final String RC_KEY_RUN_SURFACE = "a2ui.runSurfaceId";

    private A2uiConstants() {}
}

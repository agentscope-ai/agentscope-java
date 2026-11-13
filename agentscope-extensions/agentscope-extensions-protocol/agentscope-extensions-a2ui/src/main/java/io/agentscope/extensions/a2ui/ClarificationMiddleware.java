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
package io.agentscope.extensions.a2ui;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.a2ui.tool.AskUserQuestionTool;

/**
 * The SDK's built-in HITL clarification capability as a plain middleware (spec §9, v1.10):
 * attach with {@code HarnessAgent.builder().middleware(new ClarificationMiddleware())} to give
 * the agent the {@code ask_user_question} tool, independent of any A2UI toggle.
 *
 * <p>Registers the <em>plain</em> flavour of the tool ({@code enableA2ui=false}): the tool
 * validates the structured questions and suspends with the canonical {@code {"questions":[…]}}
 * JSON, which an AG-UI frontend renders as a native plain-text question card — no catalog,
 * renderer or A2UI runtime involved.
 *
 * <p>When A2UI is enabled, {@link A2uiMiddleware} registers the same tool name with
 * {@code enableA2ui=true} (questions compiled into an A2UI form envelope). {@code
 * Toolkit#registerTool} is a map put, so mount exactly one of the two; if both are mounted, the
 * middleware installed last wins.
 */
public final class ClarificationMiddleware implements MiddlewareBase, ToolInstallingMiddleware {

    @Override
    public void installTools(Toolkit toolkit) {
        toolkit.registerTool(new AskUserQuestionTool(null, false));
    }
}

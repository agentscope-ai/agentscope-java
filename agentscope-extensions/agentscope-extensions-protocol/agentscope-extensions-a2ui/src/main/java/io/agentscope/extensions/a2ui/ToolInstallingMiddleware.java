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

import io.agentscope.core.tool.Toolkit;

/**
 * A middleware that contributes real (locally executed) tools to the hosting agent.
 *
 * <p>The framework stopped offering a build-time callback for this: middleware can no longer install
 * tools into an agent's toolkit, and per-call tool injection ({@code ToolRequestConfig}) is
 * restricted to schema-only external tools. A middleware that owns an implemented tool therefore
 * exposes it here for the assembler to install.
 *
 * <p>Contract: call {@link #installTools(Toolkit)} exactly once per agent build, after {@code
 * build()}, against {@code agent.getDelegate().getToolkit()} — that toolkit is the agent's own deep
 * copy, so installing never leaks tools into a toolkit shared with other agents. When several
 * middlewares register the same tool name, the last call wins (tool registration is a map put), so
 * the assembler must install in override order.
 */
public interface ToolInstallingMiddleware {

    /**
     * Register this middleware's tools on the agent's toolkit.
     *
     * @param toolkit the built agent's own toolkit; must not be {@code null}
     */
    void installTools(Toolkit toolkit);
}

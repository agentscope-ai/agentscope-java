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
package io.agentscope.core.tool.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared factory for mocked {@link McpClientWrapper} instances.
 *
 * <p>Mockito stubs unstubbed boolean methods to {@code false}, which would silently drive
 * {@link McpTool#callAsync} down the "meta omitted" path instead of the propagation path. Tests
 * that exercise {@link McpTool} against a mocked wrapper must therefore create the mock through
 * this helper so the connection-level switch is set explicitly and the intent is visible.
 */
public final class McpClientWrapperTestSupport {

    private McpClientWrapperTestSupport() {}

    /**
     * Creates a mocked wrapper with {@code isPropagateMeta()} explicitly stubbed.
     *
     * @param name value returned by {@link McpClientWrapper#getName()}
     * @param propagateMeta value returned by {@link McpClientWrapper#isPropagateMeta()}
     * @return the mocked wrapper
     */
    public static McpClientWrapper mockWrapper(String name, boolean propagateMeta) {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn(name);
        when(wrapper.isPropagateMeta()).thenReturn(propagateMeta);
        return wrapper;
    }
}

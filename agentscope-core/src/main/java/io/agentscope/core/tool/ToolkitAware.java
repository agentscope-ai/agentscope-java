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
package io.agentscope.core.tool;

/**
 * Implemented by middleware (or other components) that hold a reference to a {@link Toolkit}.
 *
 * <p>During {@code ReActAgent.Builder.build()}, the builder deep-copies the toolkit to isolate
 * agent state. Any externally-constructed component that was handed the original toolkit will
 * hold a stale reference after the copy. The builder detects components that implement this
 * interface and calls {@link #rebindToolkit(Toolkit)} with the deep-copied instance, so they
 * transparently switch to the correct toolkit without requiring a factory method on the builder.
 */
public interface ToolkitAware {

    /**
     * Replace this component's toolkit reference with the given instance.
     *
     * <p>Called by the agent builder after deep-copying the toolkit. Implementations should
     * update their internal toolkit reference and, if necessary, re-register any tools they
     * had previously registered on the old toolkit.
     *
     * @param toolkit the deep-copied toolkit that the agent will actually use; never {@code null}
     */
    void rebindToolkit(Toolkit toolkit);
}

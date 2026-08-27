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

import java.nio.file.Path;
import java.util.Optional;

/**
 * Contract for tool objects that can translate a raw path argument into the exact absolute
 * location the tool's execution would operate on, <em>without side effects</em>.
 *
 * <p>The permission gate uses this to evaluate {@code ACCEPT_EDITS} working-directory auto-allow
 * on the real execution landing point instead of a guessed base. Implementations must mirror the
 * tool's execution semantics exactly (same {@code ~} expansion, same root, same relativisation
 * rules); when the landing point cannot be determined with certainty, return {@link
 * Optional#empty()} &mdash; the gate then fails closed (the call is not auto-allowed) rather than
 * guessing.
 *
 * <p>Only tool classes registered through {@code Toolkit#registerTool(Object)} (i.e. with
 * {@code @Tool} methods) participate: the reflective wrapper delegates to this interface when
 * the tool object implements it, so subclasses of {@code ToolBase} declared directly on the
 * builder continue to use the protected {@link ToolBase#resolveExecutionPath(String)} default
 * (absolute-only, fail-closed for relative paths).
 *
 * <p><b>Known limitation.</b> This resolver resolves the path <em>lexically</em>; the resulting
 * location is symlink-resolved separately by the permission gate before the working-directory
 * containment check. Tools whose execution resolves symlinks in a tool-specific way (e.g.
 * through their own filesystem abstraction) should account for that when implementing this
 * method.
 */
public interface ToolFilePathResolver {

    /**
     * @param rawPath the raw value of a declared file-path parameter
     * @return the absolute execution path when it can be proven with certainty; empty when it
     *     cannot
     */
    Optional<Path> resolveToolFilePath(String rawPath);
}

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
 * Built-in tools that participate in permission evaluation via
 * {@link io.agentscope.core.tool.permission.ToolBase}.
 *
 * <p>This package hosts the six core tools used by agents to inspect and modify the local
 * environment:
 *
 * <ul>
 *   <li>{@link io.agentscope.core.tool.builtin.Read} — read a file slice into the agent
 *       read-cache so subsequent writes are constrained by the Read-before-Write invariant.
 *   <li>{@link io.agentscope.core.tool.builtin.Write} — create or replace a file; requires a
 *       prior {@link io.agentscope.core.tool.builtin.Read} cache hit for existing files.
 *   <li>{@link io.agentscope.core.tool.builtin.Edit} — replace a substring in an existing file
 *       with single-occurrence enforcement unless {@code replace_all} is true.
 *   <li>{@link io.agentscope.core.tool.builtin.Glob} — list filesystem entries matching a glob
 *       pattern, ordered by descending modification time.
 *   <li>{@link io.agentscope.core.tool.builtin.Grep} — content search by delegating to a local
 *       {@code rg} (ripgrep) binary.
 *   <li>{@link io.agentscope.core.tool.builtin.Bash} — execute a shell command after AST-level
 *       safety analysis (see {@link io.agentscope.core.tool.builtin.bash}).
 * </ul>
 *
 * <p>The legacy {@code io.agentscope.core.tool.file} and {@code io.agentscope.core.tool.coding}
 * packages are retained for backward compatibility; new code should prefer the tools defined
 * here.
 */
package io.agentscope.core.tool.builtin;

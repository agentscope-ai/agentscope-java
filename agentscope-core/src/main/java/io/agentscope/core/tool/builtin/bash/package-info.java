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
 * Bash command parsing and danger detection.
 *
 * <p>{@link io.agentscope.core.tool.builtin.bash.BashCommandParser} wraps the tree-sitter Bash
 * grammar to extract command structure, file paths, redirections, command prefixes, and risky
 * patterns (command substitution, process substitution, loops) from a shell command string.
 * {@link io.agentscope.core.tool.builtin.bash.BashConstants} holds the well-known safe and
 * dangerous command sets consulted by the parser.
 *
 * <p>The classes here are stateless utilities consumed by
 * {@link io.agentscope.core.tool.builtin.Bash}; they have no dependency on the broader tool or
 * permission packages.
 */
package io.agentscope.core.tool.builtin.bash;

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
 * Tool-protocol base types co-located with the permission engine for
 * AgentScope Java 2.0 (Stage 2).
 *
 * <p>This sub-package isolates the new {@code ToolBase} abstract class plus
 * {@code ToolContext}, {@code ReadCacheEntry}, and other safety-metadata
 * carriers from the v1 {@code tool/Tool.java} surface, allowing the two
 * surfaces to coexist while {@code Toolkit} migrates to accept the new base.
 *
 * <p>Placeholder for Stage 2 — see
 * {@code docs/v2-design/proposal-delta.md} entries T1–T6.
 */
package io.agentscope.core.tool.permission;

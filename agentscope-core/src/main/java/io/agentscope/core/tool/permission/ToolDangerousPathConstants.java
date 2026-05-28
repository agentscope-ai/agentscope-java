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
package io.agentscope.core.tool.permission;

import java.util.List;

/**
 * @deprecated Use {@link io.agentscope.core.tool.ToolDangerousPathConstants} directly. This alias
 *     remains for one minor version and will be removed in the next.
 */
@Deprecated(forRemoval = true)
public final class ToolDangerousPathConstants {

    private ToolDangerousPathConstants() {}

    public static final List<String> DEFAULT_DANGEROUS_FILES =
            io.agentscope.core.tool.ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES;

    public static final List<String> DEFAULT_DANGEROUS_DIRECTORIES =
            io.agentscope.core.tool.ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES;

    public static final List<String> DANGEROUS_COMMANDS =
            io.agentscope.core.tool.ToolDangerousPathConstants.DANGEROUS_COMMANDS;
}

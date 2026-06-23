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
package io.agentscope.core.util;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

/**
 * Utility class for toolkit.
 */
public class ToolUtils {

    public static String resolveToolTitle(Toolkit toolkit, String toolName) {
        if (toolName == null) {
            return null;
        }
        if (toolkit == null) {
            return null;
        }
        AgentTool tool = toolkit.getTool(toolName);
        return tool != null ? tool.getTitle() : null;
    }
}

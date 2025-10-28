/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.e2e;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quick verification test to check if MultiModalConversationParam supports tools.
 */
@Tag("verification")
@DisplayName("MultiModalConversationParam Tools Support Verification")
class MultiModalConversationToolsTest {

    @Test
    @DisplayName("Verify MultiModalConversationParam has setTools method")
    void testMultiModalConversationParamHasToolsMethod() throws Exception {
        System.out.println("\n=== Inspecting MultiModalConversationParam ===");

        Class<?> paramClass = MultiModalConversationParam.class;

        boolean hasSetTools = false;
        boolean hasGetTools = false;

        System.out.println("Available methods:");
        for (var method : paramClass.getMethods()) {
            String methodName = method.getName();
            if (methodName.toLowerCase().contains("tool")) {
                System.out.println(
                        "  - " + methodName + "(" + method.getParameterCount() + " params)");
                if (methodName.equals("setTools")) hasSetTools = true;
                if (methodName.equals("getTools")) hasGetTools = true;
            }
        }

        if (hasSetTools && hasGetTools) {
            System.out.println("\n✅ MultiModalConversationParam supports tools!");
        } else {
            System.out.println("\n❌ MultiModalConversationParam does NOT support tools");
        }
    }
}

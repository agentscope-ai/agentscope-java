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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CompressionLanguageHintResolver Tests")
class CompressionLanguageHintResolverTest {

    @Test
    @DisplayName("Should infer Chinese requirement from first user message")
    void testInferChineseRequirementFromFirstUserMessage() {
        String requirement =
                CompressionLanguageHintResolver.inferLanguageRequirement(
                        List.of(
                                msg(MsgRole.USER, "请用中文总结一下这个结果"),
                                msg(MsgRole.ASSISTANT, "I will summarize it.")));

        assertTrue(requirement.contains("primarily in Chinese"));
        assertTrue(requirement.contains("pinyin"));
    }

    @Test
    @DisplayName("Should infer English requirement from first user message")
    void testInferEnglishRequirementFromFirstUserMessage() {
        String requirement =
                CompressionLanguageHintResolver.inferLanguageRequirement(
                        List.of(
                                msg(
                                        MsgRole.USER,
                                        "Please summarize the previous steps in English."),
                                msg(MsgRole.ASSISTANT, "好的，我会总结。")));

        assertTrue(requirement.contains("primarily in English"));
        assertTrue(requirement.contains("embedded Chinese"));
    }

    @Test
    @DisplayName("Should prefer first user message over later assistant language")
    void testInferRequirementPrefersFirstUserMessage() {
        String requirement =
                CompressionLanguageHintResolver.inferLanguageRequirement(
                        List.of(
                                msg(MsgRole.USER, "北京市海淀区中关村软件园"),
                                msg(MsgRole.ASSISTANT, "The office is located in Beijing."),
                                msg(MsgRole.USER, "Please keep the important details.")));

        assertTrue(requirement.contains("primarily in Chinese"));
    }

    @Test
    @DisplayName("Should fall back to same-language requirement when no text exists")
    void testInferRequirementFallsBackWithoutText() {
        String requirement =
                CompressionLanguageHintResolver.inferLanguageRequirement(
                        List.of(msg(MsgRole.USER, "   "), msg(MsgRole.ASSISTANT, "")));

        assertTrue(requirement.contains("same primary language"));
    }

    @Test
    @DisplayName("Should append language requirement to base prompt")
    void testAppendLanguageRequirement() {
        String prompt =
                CompressionLanguageHintResolver.appendLanguageRequirement(
                        "Base prompt", List.of(msg(MsgRole.USER, "请继续用中文压缩上下文")));

        assertTrue(prompt.startsWith("Base prompt"));
        assertTrue(prompt.contains("LANGUAGE REQUIREMENT"));
        assertTrue(prompt.contains("primarily in Chinese"));
    }

    private static Msg msg(MsgRole role, String text) {
        return Msg.builder()
                .role(role)
                .name(role.name().toLowerCase())
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}

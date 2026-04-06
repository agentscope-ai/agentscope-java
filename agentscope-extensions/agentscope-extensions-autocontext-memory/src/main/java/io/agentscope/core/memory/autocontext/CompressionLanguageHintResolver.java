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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;

/** Resolves language-preservation hints for AutoContextMemory compression prompts. */
final class CompressionLanguageHintResolver {

    private static final String SAME_LANGUAGE_REQUIREMENT =
            "LANGUAGE REQUIREMENT:\n"
                    + "Write the compressed result in the same primary language as the first user"
                    + " message in the provided conversation. Preserve multilingual fragments,"
                    + " technical terms, IDs, paths, and proper nouns exactly as they appear in"
                    + " the source content.";

    private static final String CHINESE_LANGUAGE_REQUIREMENT =
            "LANGUAGE REQUIREMENT:\n"
                    + "Write the compressed result primarily in Chinese to match the user's"
                    + " language. Do not translate Chinese names, addresses, or domain-specific"
                    + " phrases into English or pinyin unless the original content already used"
                    + " that form. Preserve embedded English technical terms, IDs, paths, and"
                    + " proper nouns exactly as they appear in the source content.";

    private static final String ENGLISH_LANGUAGE_REQUIREMENT =
            "LANGUAGE REQUIREMENT:\n"
                    + "Write the compressed result primarily in English to match the user's"
                    + " language. Preserve embedded Chinese or other multilingual fragments,"
                    + " technical terms, IDs, paths, and proper nouns exactly as they appear in"
                    + " the source content.";

    private CompressionLanguageHintResolver() {}

    static String appendLanguageRequirement(String basePrompt, List<Msg> messages) {
        return basePrompt + "\n\n" + inferLanguageRequirement(messages);
    }

    static String inferLanguageRequirement(List<Msg> messages) {
        String referenceText = extractReferenceText(messages);
        if (referenceText.isBlank()) {
            return SAME_LANGUAGE_REQUIREMENT;
        }

        LanguagePreference languagePreference = detectLanguagePreference(referenceText);
        if (languagePreference == LanguagePreference.CHINESE) {
            return CHINESE_LANGUAGE_REQUIREMENT;
        }
        if (languagePreference == LanguagePreference.ENGLISH) {
            return ENGLISH_LANGUAGE_REQUIREMENT;
        }
        return SAME_LANGUAGE_REQUIREMENT;
    }

    private static String extractReferenceText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        for (Msg message : messages) {
            if (message == null || message.getRole() != MsgRole.USER) {
                continue;
            }
            String text = message.getTextContent();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }

        StringBuilder fallback = new StringBuilder();
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!fallback.isEmpty()) {
                fallback.append('\n');
            }
            fallback.append(text);
        }
        return fallback.toString();
    }

    private static LanguagePreference detectLanguagePreference(String text) {
        int chineseChars = 0;
        int latinChars = 0;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(current);
            if (script == Character.UnicodeScript.HAN) {
                chineseChars++;
                continue;
            }
            if (isLatinLetter(current)) {
                latinChars++;
            }
        }

        if (chineseChars == 0 && latinChars == 0) {
            return LanguagePreference.UNKNOWN;
        }
        if (chineseChars >= latinChars && chineseChars > 0) {
            return LanguagePreference.CHINESE;
        }
        if (latinChars > 0) {
            return LanguagePreference.ENGLISH;
        }
        return LanguagePreference.UNKNOWN;
    }

    private static boolean isLatinLetter(char current) {
        return Character.isLetter(current)
                && Character.UnicodeScript.of(current) == Character.UnicodeScript.LATIN;
    }

    private enum LanguagePreference {
        CHINESE,
        ENGLISH,
        UNKNOWN,
    }
}

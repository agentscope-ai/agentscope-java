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
package io.agentscope.examples.advanced.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;

/**
 * Utility class for handling Msg objects.
 */
public final class MsgUtils {

    private MsgUtils() {
        // Utility class, no instantiation
    }

    /**
     * Extracts text content from a Msg object.
     *
     * @param msg the message to extract text from
     * @return the concatenated text content, or empty string if null
     */
    public static String getTextContent(Msg msg) {
        if (msg == null) {
            return "";
        }

        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }
}

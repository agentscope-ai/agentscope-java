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
package io.agentscope.harness.agent.context;

import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;

/**
 * Replace with an adapter-specific tokenizer when available. Estimates are not provider usage.
 *
 * <p>Text is counted per Unicode category: ASCII at {@code 2.5} characters per token, BMP
 * non-ASCII (CJK and other multi-byte scripts) at one token per character, and supplementary code
 * points (emoji and rare scripts) at two tokens each. The result is never lower than the uniform
 * {@code length / 2.5} baseline it replaces. A structured-output response format that travels with
 * the request — including one supplied by the model's default options — is priced as part of the
 * input surface.
 */
@FunctionalInterface
public interface ContextTokenEstimator {
    Estimate estimate(ModelCallInput input);

    record Estimate(long tokens, String method, boolean exact) {
        public Estimate {
            if (tokens < 0 || method == null)
                throw new IllegalArgumentException("Invalid estimate");
        }
    }

    static ContextTokenEstimator approximate() {
        return input -> {
            long count = 0;
            for (var msg : input.messages()) {
                count += 12 + text(msg.getName());
                for (var block : msg.getContent()) count += block(block);
            }
            for (var tool : input.tools()) {
                count += 16 + text(JsonUtils.getJsonCodec().toJson(tool));
            }
            ResponseFormat responseFormat = effectiveResponseFormat(input);
            if (responseFormat != null) {
                count += 16 + text(JsonUtils.getJsonCodec().toJson(responseFormat));
            }
            return new Estimate(count, "mixed-ascii2.5-cjk1-supp2-media-2048-v1", false);
        };
    }

    /** Reads the response format the provider will actually send after applying model defaults. */
    private static ResponseFormat effectiveResponseFormat(ModelCallInput input) {
        GenerateOptions options =
                input.model() == null
                        ? input.options()
                        : input.model().resolveOptions(input.options());
        return options == null ? null : options.getResponseFormat();
    }

    private static long text(String value) {
        if (value == null || value.isEmpty()) return 0;
        long ascii = 0;
        long bmpNonAscii = 0;
        long supplementary = 0;
        int i = 0;
        int length = value.length();
        while (i < length) {
            char c = value.charAt(i);
            if (c < 0x80) {
                ascii++;
                i++;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < length
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                supplementary++;
                i += 2;
            } else {
                bmpNonAscii++;
                i++;
            }
        }
        return (long) Math.ceil(ascii / 2.5) + bmpNonAscii + supplementary * 2;
    }

    private static long block(ContentBlock block) {
        if (block instanceof TextBlock text) return text(text.getText());
        if (block instanceof ThinkingBlock thinking) return text(thinking.getThinking());
        if (block instanceof ToolUseBlock use) {
            return 12
                    + text(use.getId())
                    + text(use.getName())
                    + text(JsonUtils.getJsonCodec().toJson(use.getInput()))
                    + text(use.getContent());
        }
        if (block instanceof ToolResultBlock result) {
            long tokens = 12 + text(result.getId()) + text(result.getName());
            for (var output : result.getOutput()) tokens += block(output);
            return tokens;
        }
        // Explicitly approximate: media cost depends on provider, resolution and duration.
        return 2048;
    }
}

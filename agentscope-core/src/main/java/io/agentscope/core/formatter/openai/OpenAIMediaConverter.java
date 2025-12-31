/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.openai;

import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles media content conversion for OpenAI API.
 */
public class OpenAIMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMediaConverter.class);

    /**
     * Convert ImageBlock to OpenAI ChatCompletionContentPart.
     * Converts local files to base64 data URLs.
     */
    public ChatCompletionContentPart convertImageBlockToContentPart(ImageBlock imageBlock)
            throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                String dataUrl = MediaUtils.urlToBase64DataUrl(url);
                return ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                                .imageUrl(
                                        ChatCompletionContentPartImage.ImageUrl.builder()
                                                .url(dataUrl)
                                                .build())
                                .build());
            } else {
                return ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                                .imageUrl(
                                        ChatCompletionContentPartImage.ImageUrl.builder()
                                                .url(url)
                                                .build())
                                .build());
            }
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            String dataUrl = String.format("data:%s;base64,%s", mediaType, base64Data);

            return ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(
                                    ChatCompletionContentPartImage.ImageUrl.builder()
                                            .url(dataUrl)
                                            .build())
                            .build());
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert AudioBlock to OpenAI ChatCompletionContentPart.
     */
    public ChatCompletionContentPart convertAudioBlockToContentPart(AudioBlock audioBlock)
            throws Exception {
        Source source = audioBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateAudioExtension(url);
            ChatCompletionContentPartInputAudio.InputAudio.Format format =
                    MediaUtils.determineAudioFormat(url);

            return ChatCompletionContentPart.ofInputAudio(
                    ChatCompletionContentPartInputAudio.builder()
                            .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                            .data(url)
                                            .format(format)
                                            .build())
                            .build());
        } else if (source instanceof Base64Source base64Source) {
            String base64Data = base64Source.getData();
            String mediaType = base64Source.getMediaType();
            ChatCompletionContentPartInputAudio.InputAudio.Format format =
                    MediaUtils.inferAudioFormatFromMediaType(mediaType);

            return ChatCompletionContentPart.ofInputAudio(
                    ChatCompletionContentPartInputAudio.builder()
                            .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                            .data(base64Data)
                                            .format(format)
                                            .build())
                            .build());
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Create an error text ContentPart for fallback.
     */
    public ChatCompletionContentPart createErrorTextPart(String text) {
        return ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(text).build());
    }
}

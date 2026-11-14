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

package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages;
import io.agentscope.durable.DurableProtocol;
import java.util.ArrayList;
import java.util.List;

/** Explicit native/public conversion; arbitrary native JSON never becomes public output. */
final class AgentScopeMessages {
    private AgentScopeMessages() {}

    static Msg input(JsonNode value, String id) {
        if (value.isTextual())
            return UserMessage.builder().id(id).textContent(value.asText()).build();
        DurableProtocol.validate("input", value);
        var input = DurableJson.convert(value, DurableMessages.Input.class);
        var content = new ArrayList<ContentBlock>();
        for (var block : input.content()) {
            if (block instanceof DurableMessages.Text text)
                content.add(TextBlock.builder().text(text.text()).build());
            else if (block instanceof DurableMessages.Image image)
                content.add(new ImageBlock(source(image.source())));
            else if (block instanceof DurableMessages.Audio audio)
                content.add(new AudioBlock(source(audio.source())));
            else if (block instanceof DurableMessages.Video video)
                content.add(new VideoBlock(source(video.source())));
        }
        return UserMessage.builder().id(id).content(content).build();
    }

    static DurableMessages.Message output(Msg message, String id) {
        return message(message, id, "assistant");
    }

    private static DurableMessages.Message message(Msg message, String id, String role) {
        return new DurableMessages.Message("1", id, role, content(message.getContent()));
    }

    static List<DurableMessages.Content> content(List<ContentBlock> blocks) {
        var content = new ArrayList<DurableMessages.Content>();
        for (var block : blocks) {
            if (block instanceof TextBlock text)
                content.add(new DurableMessages.Text(text.getText()));
            else if (block instanceof ImageBlock image)
                content.add(new DurableMessages.Image(source(image.getSource())));
            else if (block instanceof AudioBlock audio)
                content.add(new DurableMessages.Audio(source(audio.getSource())));
            else if (block instanceof VideoBlock video)
                content.add(new DurableMessages.Video(source(video.getSource())));
        }
        return List.copyOf(content);
    }

    private static Source source(DurableMessages.Source source) {
        if (source instanceof DurableMessages.Url url)
            return new URLSource(url.url(), url.mimeType());
        if (source instanceof DurableMessages.Base64 base64)
            return new Base64Source(base64.mimeType(), base64.data());
        throw new DurableException(DurableException.Code.INVALID, "Unsupported content source");
    }

    private static DurableMessages.Source source(Source source) {
        if (source instanceof URLSource url)
            return new DurableMessages.Url(url.getUrl(), url.getMimeType());
        if (source instanceof Base64Source base64)
            return new DurableMessages.Base64(base64.getMediaType(), base64.getData());
        throw new DurableException(
                DurableException.Code.INVALID, "Unsupported native content source");
    }
}

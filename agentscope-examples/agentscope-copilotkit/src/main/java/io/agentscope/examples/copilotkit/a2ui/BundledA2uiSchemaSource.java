/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.examples.copilotkit.a2ui;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Default {@link A2uiSchemaSource}: a compact hand-written A2UI v0.9 grammar plus the structural
 * checks in {@link A2uiOperations}.
 */
@Component
public class BundledA2uiSchemaSource implements A2uiSchemaSource {

    private static final Pattern TAGGED_BLOCK =
            Pattern.compile("<a2ui-json>(.*?)</a2ui-json>", Pattern.DOTALL);
    private static final Pattern FENCED_BLOCK =
            Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

    @Override
    public String name() {
        return "bundled-a2ui-v0.9";
    }

    @Override
    public String systemPrompt(Set<String> clientComponents) {
        return "你是 AgentScope 工作台的 A2UI 生成器。根据用户意图和给定数据，输出一份 A2UI v0.9 界面描述。\n\n"
                + WorkbenchCatalog.grammar(clientComponents);
    }

    @Override
    public List<Map<String, Object>> parse(
            String rawModelReply, String surfaceId, Set<String> clientComponents) {
        if (rawModelReply == null || rawModelReply.isBlank()) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        List<Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(extractJsonArray(rawModelReply), new TypeReference<>() {});
        return A2uiOperations.normalize(parsed, surfaceId);
    }

    /** Pulls the JSON array out of the model reply, tolerating tags, fences and stray prose. */
    private static String extractJsonArray(String raw) {
        Matcher tagged = TAGGED_BLOCK.matcher(raw);
        if (tagged.find()) {
            return stripFence(tagged.group(1));
        }
        Matcher fenced = FENCED_BLOCK.matcher(raw);
        if (fenced.find()) {
            return fenced.group(1).trim();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        throw new IllegalArgumentException("模型输出中找不到 A2UI JSON 数组");
    }

    private static String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").trim();
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }
}

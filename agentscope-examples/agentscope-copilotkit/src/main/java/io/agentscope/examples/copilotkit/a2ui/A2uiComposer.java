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

import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Turns a natural-language intent into a renderable A2UI surface.
 *
 * <p>Model clients are tried in order (Gemini first, DashScope second). Every failure — no
 * credentials, a network error, malformed JSON, a component outside the catalog — falls through to
 * the next client, and finally to the deterministic {@link A2uiFallbackSurface}, so
 * {@code render_dashboard} always returns something the browser can paint.
 */
@Component
public class A2uiComposer {

    private static final Logger logger = LoggerFactory.getLogger(A2uiComposer.class);

    private final A2uiSchemaSource schemaSource;
    private final List<A2uiModelClient> modelClients;

    public A2uiComposer(A2uiSchemaSource schemaSource, List<A2uiModelClient> modelClients) {
        this.schemaSource = schemaSource;
        this.modelClients =
                modelClients.stream().sorted(AnnotationAwareOrderComparator.INSTANCE).toList();
    }

    public String schemaSourceName() {
        return schemaSource.name();
    }

    /**
     * Composes a surface for {@code intent}.
     *
     * @param intent what the user asked to see
     * @param stateSnapshot the current workbench state, used both as model input and as the source
     *     of truth for the deterministic fallback
     * @param clientComponents custom components the browser declared it can render; empty
     *     advertises the whole server-side catalog
     */
    public A2uiSurface compose(
            String intent, Map<String, Object> stateSnapshot, Set<String> clientComponents) {
        String surfaceId = "workbench-" + UUID.randomUUID().toString().substring(0, 8);
        String systemPrompt = schemaSource.systemPrompt(clientComponents);
        String userPrompt = buildUserPrompt(intent, surfaceId, stateSnapshot);

        for (A2uiModelClient client : modelClients) {
            if (!client.isAvailable()) {
                continue;
            }
            try {
                String raw = client.generate(systemPrompt, userPrompt);
                List<Map<String, Object>> operations =
                        schemaSource.parse(raw, surfaceId, clientComponents);
                return new A2uiSurface(
                        surfaceId,
                        WorkbenchCatalog.CATALOG_ID,
                        intent,
                        client.name(),
                        A2uiOperations.countComponents(operations),
                        operations);
            } catch (Exception e) {
                logger.warn("A2UI 生成失败，切换下一个模型：client={}, reason={}", client.name(), e.toString());
            }
        }

        return A2uiFallbackSurface.build(surfaceId, intent, stateSnapshot);
    }

    private static String buildUserPrompt(
            String intent, String surfaceId, Map<String, Object> stateSnapshot) {
        return """
        请生成界面。

        surfaceId: %s
        用户意图: %s

        可用数据（JSON，可通过 updateDataModel 绑定，也可直接内联为字面量）：
        %s
        """
                .formatted(surfaceId, intent, toJson(stateSnapshot));
    }

    private static String toJson(Object value) {
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}

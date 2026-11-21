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
package io.agentscope.extensions.a2ui.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The A2UI v1.0 transport wrapper: {@code {"messages": [...]}} where each message is {@code
 * {"version": "1.0", "<operation>": {...}}}. The serialized form <em>is</em> the tool result text
 * that the frontend hands to the renderer.
 *
 * <p>The wrapper is closed ({@code additionalProperties: false}), so an advisory note for the model
 * cannot ride beside {@code messages}. It travels in the root component's {@code
 * metadata.extensions} instead — the protocol's vendor-extension slot, which every component carries
 * and renderers ignore. Component level rather than surface level because {@code updateComponents}
 * has no metadata of its own.
 *
 * @param messages the operation messages, in delivery order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2uiEnvelope(List<Map<String, Object>> messages) {

    private static final String ROOT_ID = "root";

    /** A surface-creating message carrying its component tree and optional initial data model. */
    public static A2uiEnvelope createSurface(
            String surfaceId,
            String catalogId,
            List<Map<String, Object>> components,
            Map<String, Object> dataModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaceId", surfaceId);
        body.put("catalogId", catalogId);
        body.put("components", components);
        if (dataModel != null && !dataModel.isEmpty()) {
            body.put("dataModel", dataModel);
        }
        return new A2uiEnvelope(List.of(message(A2uiConstants.MESSAGE_TYPE_CREATE_SURFACE, body)));
    }

    /**
     * A message replacing the component tree of a surface that already exists, followed by a data
     * model update when one is given — {@code updateComponents} carries no data model of its own.
     */
    public static A2uiEnvelope updateComponents(
            String surfaceId, List<Map<String, Object>> components, Map<String, Object> dataModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaceId", surfaceId);
        body.put("components", components);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message(A2uiConstants.MESSAGE_TYPE_UPDATE_COMPONENTS, body));
        if (dataModel != null && !dataModel.isEmpty()) {
            messages.add(
                    message(
                            A2uiConstants.MESSAGE_TYPE_UPDATE_DATA_MODEL,
                            dataModelBody(surfaceId, dataModel)));
        }
        return new A2uiEnvelope(messages);
    }

    /** Omitting {@code path} addresses the whole data model. */
    private static Map<String, Object> dataModelBody(
            String surfaceId, Map<String, Object> dataModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaceId", surfaceId);
        body.put("value", dataModel);
        return body;
    }

    private static Map<String, Object> message(String operation, Map<String, Object> body) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("version", A2uiConstants.PROTOCOL_VERSION);
        message.put(operation, body);
        return message;
    }

    /** The first message's operation name, or {@code null} when there is none. */
    public String messageType() {
        Map<String, Object> first = firstMessage();
        if (first == null) {
            return null;
        }
        for (String operation : A2uiConstants.OPERATIONS) {
            if (first.get(operation) instanceof Map) {
                return operation;
            }
        }
        return null;
    }

    public String surfaceId() {
        Object value = operationBody().get("surfaceId");
        return value instanceof String s ? s : null;
    }

    /** The first message's component list; empty when the operation carries none. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> components() {
        Object value = operationBody().get("components");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** The advisory note carried in the root component's vendor extensions, or {@code null}. */
    public String note() {
        Map<String, Object> root = rootComponent();
        if (root == null || !(root.get("metadata") instanceof Map<?, ?> metadata)) {
            return null;
        }
        if (!(metadata.get("extensions") instanceof Map<?, ?> extensions)) {
            return null;
        }
        Object value = extensions.get(A2uiConstants.EXTENSION_NOTE);
        return value instanceof String s ? s : null;
    }

    /** @return a copy of this envelope whose root component carries {@code note} */
    public A2uiEnvelope withNote(String note) {
        Map<String, Object> root = rootComponent();
        if (note == null || note.isBlank() || root == null) {
            return this;
        }
        Map<String, Object> metadata = asMap(root.get("metadata"));
        Map<String, Object> extensions = asMap(metadata.get("extensions"));
        extensions.put(A2uiConstants.EXTENSION_NOTE, note);
        metadata.put("extensions", extensions);
        Map<String, Object> annotated = new LinkedHashMap<>(root);
        annotated.put("metadata", metadata);
        return new A2uiEnvelope(messagesWithRoot(annotated));
    }

    /** Copies each message and operation body so the original envelope stays untouched. */
    private List<Map<String, Object>> messagesWithRoot(Map<String, Object> annotated) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            Map<String, Object> messageCopy = new LinkedHashMap<>(message);
            for (String operation : A2uiConstants.OPERATIONS) {
                if (!(messageCopy.get(operation) instanceof Map<?, ?> body)) {
                    continue;
                }
                Map<String, Object> bodyCopy = asMap(body);
                if (bodyCopy.get("components") instanceof List<?> components) {
                    List<Object> replaced = new ArrayList<>();
                    for (Object component : components) {
                        boolean isRoot =
                                component instanceof Map<?, ?> c && ROOT_ID.equals(c.get("id"));
                        replaced.add(isRoot ? annotated : component);
                    }
                    bodyCopy.put("components", replaced);
                }
                messageCopy.put(operation, bodyCopy);
            }
            out.add(messageCopy);
        }
        return out;
    }

    private Map<String, Object> rootComponent() {
        for (Map<String, Object> component : components()) {
            if (component != null && ROOT_ID.equals(component.get("id"))) {
                return component;
            }
        }
        return null;
    }

    private Map<String, Object> firstMessage() {
        return messages == null || messages.isEmpty() ? null : messages.get(0);
    }

    private Map<String, Object> operationBody() {
        Map<String, Object> first = firstMessage();
        if (first != null) {
            for (String operation : A2uiConstants.OPERATIONS) {
                if (first.get(operation) instanceof Map<?, ?> body) {
                    return asMap(body);
                }
            }
        }
        return Map.of();
    }

    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> out.put(String.valueOf(key), nested));
        }
        return out;
    }
}

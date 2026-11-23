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
package io.agentscope.durable.codec;

import java.util.List;
import java.util.Map;

/** Pure native-format interpretation. The service owns authorization, locks and persistence. */
public interface NativeCheckpointCodec {
    String harnessType();

    record Event(String type, String runId, boolean required, String payloadJson) {}

    record Inspection(
            boolean checkpoint,
            boolean toolDispatch,
            String stateJson,
            boolean settled,
            Map<String, List<String>> opened,
            Map<String, List<String>> closed) {}

    record Baseline(String type, String payloadJson) {}

    Inspection inspect(Event event);

    Baseline baseline(
            String payloadJson,
            String sessionId,
            String threadId,
            String userId,
            boolean fork,
            Map<String, Object> metadata);
}

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
package io.agentscope.dataagent.web.binding;

import java.util.List;

/** User-scoped preferences applied when interacting through a channel. */
public record UserBinding(
        String channelId,
        String displayLabel,
        String sessionScope,
        String language,
        List<String> enabledSkills) {

    public UserBinding {
        enabledSkills = enabledSkills != null ? List.copyOf(enabledSkills) : null;
    }
}

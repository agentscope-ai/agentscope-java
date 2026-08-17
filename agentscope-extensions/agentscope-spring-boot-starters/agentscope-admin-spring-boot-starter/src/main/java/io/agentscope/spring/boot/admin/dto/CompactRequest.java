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
package io.agentscope.spring.boot.admin.dto;

/**
 * Request body for {@code POST /v1/admin/sessions/{id}:compact}. All fields are optional; nulls
 * fall back to the starter defaults.
 *
 * @param keepLastMessages override {@code agentscope.admin.compact-keep-last-messages} for this
 *     single call (null = use default)
 * @param replaceSummary when true, the new summary replaces the existing one; otherwise it is
 *     appended
 */
public record CompactRequest(Integer keepLastMessages, Boolean replaceSummary) {

    public static CompactRequest defaults() {
        return new CompactRequest(null, null);
    }
}

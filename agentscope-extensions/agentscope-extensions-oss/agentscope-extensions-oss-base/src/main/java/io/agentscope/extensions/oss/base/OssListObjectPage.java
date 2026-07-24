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
package io.agentscope.extensions.oss.base;

import java.util.List;

/**
 * One page of results returned by {@link OssAdapter#list}.
 *
 * @param objects the object summaries in this page (never {@code null})
 * @param nextContinuationToken opaque token used to fetch the next page, or {@code null}
 *     when the current page is the last one
 */
public record OssListObjectPage(List<OssObjectSummary> objects, String nextContinuationToken) {}

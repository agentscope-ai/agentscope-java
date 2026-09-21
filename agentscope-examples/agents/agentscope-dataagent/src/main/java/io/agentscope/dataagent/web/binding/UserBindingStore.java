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
import java.util.Optional;

/** Durable storage for ordered channel preferences belonging to one user. */
public interface UserBindingStore {

    List<UserBinding> list(String userId);

    UserBinding add(String userId, UserBinding binding);

    Optional<UserBinding> update(String userId, int index, UserBinding binding);

    Optional<UserBinding> remove(String userId, int index);
}

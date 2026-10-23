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

/**
 * Provider credentials for model authentication.
 *
 * <p>{@link io.agentscope.core.credential.CredentialBase} carries the {@code id} field and exposes
 * {@code getChatModelClass()} (the consuming {@link io.agentscope.core.model.ChatModelBase}
 * subclass) plus {@code listModels()} (model catalog discovery). Concrete subclasses cover
 * DeepSeek, Kimi, and xAI. Credentials whose Java
 * model class is not yet implemented throw {@link UnsupportedOperationException} from {@code
 * getChatModelClass()} while still round-tripping through JSON for storage compatibility.
 */
package io.agentscope.core.credential;

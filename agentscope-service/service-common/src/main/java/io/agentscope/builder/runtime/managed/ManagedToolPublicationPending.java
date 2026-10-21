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
package io.agentscope.builder.runtime.managed;

/** An unconfirmed Execute publication retains the original Turn without consuming failure retries. */
public final class ManagedToolPublicationPending extends ManagedTransportException {
    public ManagedToolPublicationPending(Throwable cause) {
        super("Tool publication is unconfirmed; retain the original Turn", cause);
    }

    public static boolean contains(Throwable failure) {
        for (int depth = 0; failure != null && depth < 64; depth++, failure = failure.getCause())
            if (failure instanceof ManagedToolPublicationPending) return true;
        return false;
    }
}

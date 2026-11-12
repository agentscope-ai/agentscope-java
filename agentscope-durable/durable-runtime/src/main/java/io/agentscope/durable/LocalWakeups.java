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

package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.SessionKey;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-process fan-out; listener identity is per subscription, even for the same callback. */
final class LocalWakeups implements Wakeups {
    private record Listener(Consumer<SessionKey> callback) {}

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(SessionKey session) {
        Objects.requireNonNull(session);
        RuntimeException failure = null;
        for (var listener : listeners) {
            try {
                listener.callback().accept(session);
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public AutoCloseable subscribe(Consumer<SessionKey> callback) {
        var listener = new Listener(Objects.requireNonNull(callback));
        listeners.add(listener);
        return () -> listeners.removeIf(current -> current == listener);
    }
}

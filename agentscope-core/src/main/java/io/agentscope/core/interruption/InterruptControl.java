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
package io.agentscope.core.interruption;

import io.agentscope.core.message.Msg;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A runtime-only cooperative interrupt signal owned by one execution, never by a session. */
public final class InterruptControl {

    private final AtomicBoolean flag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userMessage = new AtomicReference<>(null);
    private final AtomicReference<InterruptSource> source =
            new AtomicReference<>(InterruptSource.USER);

    /**
     * Raise the interrupt signal.
     *
     * @param src the interrupt source; {@code null} is treated as {@link InterruptSource#USER}
     * @param msg an optional user message associated with the interruption (ignored when {@code
     *     null})
     */
    public void trigger(InterruptSource src, Msg msg) {
        this.source.set(src != null ? src : InterruptSource.USER);
        if (msg != null) {
            this.userMessage.set(msg);
        }
        this.flag.set(true);
    }

    /** @return {@code true} if this execution has been interrupted and not yet reset */
    public boolean isInterrupted() {
        return flag.get();
    }

    /** @return the current interrupt source */
    public InterruptSource getSource() {
        return source.get();
    }

    /** @return the user message associated with the interruption, or {@code null} */
    public Msg getUserMessage() {
        return userMessage.get();
    }

    /** Clear the interrupt signal back to its initial (non-interrupted) state. */
    public void reset() {
        flag.set(false);
        userMessage.set(null);
        source.set(InterruptSource.USER);
    }

    /** Build an {@link InterruptContext} snapshot of the current signal. */
    public InterruptContext toContext() {
        return InterruptContext.builder()
                .source(source.get())
                .userMessage(userMessage.get())
                .build();
    }
}

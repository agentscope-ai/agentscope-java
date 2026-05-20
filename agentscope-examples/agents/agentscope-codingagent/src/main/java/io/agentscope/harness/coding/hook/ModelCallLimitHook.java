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
package io.agentscope.harness.coding.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that enforces a global model call limit across all threads.
 *
 * <p>Mirrors open-swe's {@code ModelCallLimitMiddleware}. Terminates the run when the total
 * model call count exceeds the configured limit (default: 5000).
 */
public class ModelCallLimitHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLimitHook.class);

    private final int globalLimit;
    private final AtomicLong totalCalls = new AtomicLong(0);

    public ModelCallLimitHook(int globalLimit) {
        this.globalLimit = globalLimit;
    }

    public ModelCallLimitHook() {
        this(5000);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event.getType() != HookEventType.PRE_REASONING) {
            return Mono.just(event);
        }
        long count = totalCalls.incrementAndGet();
        if (count > globalLimit) {
            log.warn(
                    "[model-call-limit-hook] Global model call limit reached: {}/{}",
                    count,
                    globalLimit);
            return Mono.error(
                    new RuntimeException(
                            "Global model call limit exceeded: " + count + "/" + globalLimit));
        }
        return Mono.just(event);
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }
}

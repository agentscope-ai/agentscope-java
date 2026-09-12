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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/**
 * Verifies the {@link MemoryBackgroundTasks} managed-disposable mechanism that backs the
 * fix for the fire-and-forget memory task connection leak: a hung background model call must
 * be cancellable via {@link MemoryBackgroundTasks#cancelAll()} instead of leaking its HTTP
 * connection until the JVM exits.
 */
class MemoryBackgroundTasksCancelTest {

    private Disposable trackingDisposable(AtomicBoolean disposed) {
        return new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
    }

    @Test
    void cancelAllDisposesRegisteredDisposable() {
        AtomicBoolean disposed = new AtomicBoolean();
        MemoryBackgroundTasks.register(trackingDisposable(disposed));
        assertFalse(disposed.get(), "must not be disposed before cancelAll");
        MemoryBackgroundTasks.cancelAll();
        assertTrue(disposed.get(), "cancelAll must dispose the registered background task");
    }

    @Test
    void cancelAllIsIdempotent() {
        AtomicBoolean disposed = new AtomicBoolean();
        MemoryBackgroundTasks.register(trackingDisposable(disposed));
        MemoryBackgroundTasks.cancelAll();
        assertTrue(disposed.get());
        // a second cancelAll must not throw
        MemoryBackgroundTasks.cancelAll();
    }

    @Test
    void registerSkipsAlreadyDisposed() {
        AtomicBoolean disposeCalled = new AtomicBoolean();
        Disposable alreadyDisposed =
                new Disposable() {
                    @Override
                    public void dispose() {
                        disposeCalled.set(true);
                    }

                    @Override
                    public boolean isDisposed() {
                        return true;
                    }
                };
        MemoryBackgroundTasks.register(alreadyDisposed);
        MemoryBackgroundTasks.cancelAll();
        assertFalse(disposeCalled.get(), "register must skip already-disposed disposables");
    }

    @Test
    void unregisterRemovesFromTracking() {
        AtomicBoolean disposed = new AtomicBoolean();
        Disposable d = trackingDisposable(disposed);
        MemoryBackgroundTasks.register(d);
        MemoryBackgroundTasks.unregister(d);
        MemoryBackgroundTasks.cancelAll();
        assertFalse(disposed.get(), "unregistered task must not be cancelled by cancelAll");
    }

    @Test
    void cancelAllIsSafeWhenEmpty() {
        // Must not throw when nothing is registered.
        MemoryBackgroundTasks.cancelAll();
    }
}

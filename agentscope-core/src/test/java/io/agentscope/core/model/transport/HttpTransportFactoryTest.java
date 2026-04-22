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
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Tests for HttpTransportFactory.
 *
 * <p>Note: These tests modify static state and must run sequentially.
 */
@Tag("unit")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpTransportFactoryTest {

    @Test
    @Order(1)
    void testGetDefaultCreatesTransport() {
        // Start fresh
        HttpTransportFactory.shutdown();

        HttpTransport transport = HttpTransportFactory.getDefault();

        assertNotNull(transport);
        assertTrue(
                transport instanceof JdkHttpTransport,
                "Expected JdkHttpTransport but got " + transport.getClass().getName());
    }

    @Test
    @Order(2)
    void testGetDefaultReturnsSameInstance() {
        HttpTransportFactory.shutdown();

        HttpTransport first = HttpTransportFactory.getDefault();
        HttpTransport second = HttpTransportFactory.getDefault();

        assertSame(first, second);
    }

    @Test
    @Order(3)
    void testSetDefaultCustomTransport() {
        HttpTransportFactory.shutdown();
        HttpTransport custom = mock(HttpTransport.class);

        HttpTransportFactory.setDefault(custom);
        HttpTransport retrieved = HttpTransportFactory.getDefault();

        assertSame(custom, retrieved);

        // Cleanup
        HttpTransportFactory.shutdown();
    }

    @Test
    @Order(4)
    void testSetDefaultToNullAllowsNewDefault() {
        HttpTransportFactory.shutdown();

        HttpTransport first = HttpTransportFactory.getDefault();
        assertNotNull(first);

        // Shutdown clears default
        HttpTransportFactory.shutdown();

        HttpTransport second = HttpTransportFactory.getDefault();
        assertNotNull(second);
        // After shutdown, a new instance is created
    }

    @Test
    @Order(5)
    void testRegisterTransport() {
        HttpTransportFactory.shutdown();
        HttpTransport transport1 = mock(HttpTransport.class);
        HttpTransport transport2 = mock(HttpTransport.class);

        HttpTransportFactory.register(transport1);
        HttpTransportFactory.register(transport2);

        assertTrue(HttpTransportFactory.isManaged(transport1));
        assertTrue(HttpTransportFactory.isManaged(transport2));

        HttpTransportFactory.shutdown();
    }

    @Test
    @Order(6)
    void testRegisterDoesNotDuplicate() {
        HttpTransportFactory.shutdown();
        HttpTransport transport = mock(HttpTransport.class);

        HttpTransportFactory.register(transport);
        int countAfterFirst = HttpTransportFactory.getManagedTransportCount();

        HttpTransportFactory.register(transport);
        int countAfterSecond = HttpTransportFactory.getManagedTransportCount();

        assertEquals(countAfterFirst, countAfterSecond);

        HttpTransportFactory.shutdown();
    }

    @Test
    @Order(7)
    void testRegisterNullIsIgnored() {
        HttpTransportFactory.shutdown();
        int initialCount = HttpTransportFactory.getManagedTransportCount();

        HttpTransportFactory.register(null);

        assertEquals(initialCount, HttpTransportFactory.getManagedTransportCount());
    }

    @Test
    @Order(8)
    void testUnregisterTransport() {
        HttpTransportFactory.shutdown();
        HttpTransport transport = mock(HttpTransport.class);
        HttpTransportFactory.register(transport);

        assertTrue(HttpTransportFactory.isManaged(transport));

        boolean removed = HttpTransportFactory.unregister(transport);

        assertTrue(removed);
        assertFalse(HttpTransportFactory.isManaged(transport));
    }

    @Test
    @Order(9)
    void testUnregisterUnknownTransport() {
        HttpTransportFactory.shutdown();
        HttpTransport transport = mock(HttpTransport.class);

        boolean removed = HttpTransportFactory.unregister(transport);

        assertFalse(removed);
    }

    @Test
    @Order(10)
    void testShutdownClosesRegisteredTransport() {
        HttpTransportFactory.shutdown();
        HttpTransport transport = mock(HttpTransport.class);

        HttpTransportFactory.register(transport);
        HttpTransportFactory.shutdown();

        verify(transport).close();
    }

    @Test
    @Order(11)
    void testShutdownClearsManagedTransports() {
        HttpTransportFactory.shutdown();
        HttpTransport transport = mock(HttpTransport.class);
        HttpTransportFactory.register(transport);

        HttpTransportFactory.shutdown();

        assertEquals(0, HttpTransportFactory.getManagedTransportCount());
    }

    @Test
    @Order(12)
    void testSetDefaultAlsoRegisters() {
        HttpTransportFactory.shutdown();
        HttpTransport custom = mock(HttpTransport.class);

        HttpTransportFactory.setDefault(custom);

        assertTrue(HttpTransportFactory.isManaged(custom));

        HttpTransportFactory.shutdown();
    }

    @Test
    @Order(13)
    void testConcurrentGetDefaultReturnsSameInstance() throws InterruptedException {
        HttpTransportFactory.shutdown();

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<HttpTransport>[] results = new AtomicReference[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            results[index] = new AtomicReference<>();
            new Thread(
                            () -> {
                                try {
                                    startLatch.await(); // Wait for signal to start
                                    results[index].set(HttpTransportFactory.getDefault());
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    doneLatch.countDown();
                                }
                            })
                    .start();
        }

        // Signal all threads to start at once
        startLatch.countDown();
        // Wait for all threads to finish
        doneLatch.await();

        // All threads should get the same instance
        HttpTransport first = results[0].get();
        assertNotNull(first);
        for (int i = 1; i < threadCount; i++) {
            assertSame(first, results[i].get(), "Thread " + i + " got a different instance");
        }

        HttpTransportFactory.shutdown();
    }

    @Test
    @Order(14)
    void testIntegrationWithOkHttpTransport() {
        HttpTransportFactory.shutdown();

        HttpTransport custom =
                OkHttpTransport.builder()
                        .config(HttpTransportConfig.builder().maxIdleConnections(10).build())
                        .build();

        HttpTransportFactory.setDefault(custom);

        HttpTransport retrieved = HttpTransportFactory.getDefault();
        assertSame(custom, retrieved);

        HttpTransportFactory.shutdown();
    }
}

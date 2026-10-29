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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

/** Discovery on the file-backed POSIX branch. Windows runs the SQLite backend instead. */
class LocalSessionStorageTest {
    @TempDir Path directory;

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoverySkipsAtomicWriteTempsAndLockFiles() throws Exception {
        var storage = new LocalSessionStorage(directory);
        assertTrue(storage.compareAndSet("commits/journal-1.json", 0, new byte[] {1, 2, 3}));
        Files.writeString(directory.resolve("commits/journal-2.tmp"), "half written");

        assertEquals(List.of("commits/journal-1.json"), storage.listPaths("commits/"));
    }

    @Test
    void readAllPreservesMissingValuesAndPathGuards() {
        var storage = new LocalSessionStorage(directory);
        assertTrue(storage.compareAndSet("commits/one.json", 0, new byte[] {1}));

        var values = storage.readAll(List.of("commits/one.json", "commits/missing.json"));

        assertEquals(1, values.size());
        assertEquals(1, values.get("commits/one.json").version());
        assertFalse(values.containsKey("commits/missing.json"));
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.readAll(List.of("commits/one.json", "../outside")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void filesystemJournalScanUsesOneBatchConnection() throws Exception {
        withCountingSqliteDriver(
                driver -> {
                    AtomicSessionStorage storage = filesystemStorage();
                    var log = new JournalSessionLog(storage, "sessions/count");
                    var writer = log.acquire("writer", Duration.ofMinutes(2));
                    for (int seq = 1; seq <= 3; seq++)
                        log.commit(writer, "batch-" + seq, seq - 1, List.of(event(seq)));
                    driver.resetConnectionCount();

                    var events = new ArrayList<SessionEvent>();
                    log.scan(0, 3).forEach(events::add);

                    assertEquals(3, events.size());
                    // Two head reads, one path listing, and one delegated SQLite readAll.
                    assertEquals(4, driver.connectionCount());
                    assertEquals(0, driver.openConnectionCount());
                });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readAllClosesSqliteConnectionsAfterSuccessAndFailure() throws Exception {
        withCountingSqliteDriver(
                driver -> {
                    AtomicSessionStorage storage = filesystemStorage();
                    assertTrue(storage.compareAndSet("commits/one.json", 0, new byte[] {1}));
                    assertEquals(1, storage.readAll(List.of("commits/one.json")).size());
                    assertEquals(0, driver.openConnectionCount());

                    Files.writeString(database(), "not a database");
                    assertThrows(
                            SessionLogException.class,
                            () -> storage.readAll(List.of("commits/one.json")));
                    assertEquals(0, driver.openConnectionCount());
                });
    }

    @Test
    @Timeout(20)
    @EnabledOnOs(OS.WINDOWS)
    void readAllDoesNotBlockConcurrentCasWriters() throws Exception {
        withCountingSqliteDriver(
                driver -> {
                    AtomicSessionStorage storage = filesystemStorage();
                    assertTrue(storage.compareAndSet("commits/value.json", 0, new byte[] {1}));
                    var start = new CountDownLatch(1);
                    var pool = Executors.newFixedThreadPool(2);
                    try {
                        var reader =
                                pool.submit(
                                        () -> {
                                            start.await();
                                            for (int i = 0; i < 25; i++)
                                                assertNotNull(
                                                        storage.readAll(
                                                                        List.of(
                                                                                "commits/value.json"))
                                                                .get("commits/value.json"));
                                            return null;
                                        });
                        var writer =
                                pool.submit(
                                        () -> {
                                            start.await();
                                            for (int version = 1; version <= 25; version++)
                                                assertTrue(
                                                        storage.compareAndSet(
                                                                "commits/value.json",
                                                                version,
                                                                new byte[] {(byte) (version + 1)}));
                                            return null;
                                        });

                        start.countDown();
                        reader.get(10, TimeUnit.SECONDS);
                        writer.get(10, TimeUnit.SECONDS);
                    } finally {
                        pool.shutdownNow();
                        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
                    }
                    assertEquals(0, driver.openConnectionCount());
                });
    }

    private AtomicSessionStorage filesystemStorage() {
        return new LocalFilesystem(directory).sessionStorage(RuntimeContext.empty());
    }

    private Path database() {
        return directory.resolve(".agentscope-runtime/journal.sqlite3");
    }

    private static SessionEvent event(int seq) {
        return new SessionEvent(
                1, "event-" + seq, seq, seq, "test/event", "run", "turn", true, "{}");
    }

    private static void withCountingSqliteDriver(CheckedAction action) throws Exception {
        var counting = new CountingDriver();
        var removed = new ArrayList<Driver>();
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.acceptsURL("jdbc:sqlite::memory:")) {
                DriverManager.deregisterDriver(driver);
                removed.add(driver);
            }
        }
        DriverManager.registerDriver(counting);
        try {
            action.run(counting);
        } finally {
            DriverManager.deregisterDriver(counting);
            for (Driver driver : removed) DriverManager.registerDriver(driver);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run(CountingDriver driver) throws Exception;
    }

    private static final class CountingDriver implements Driver {
        private final Driver delegate = new JDBC();
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger openConnections = new AtomicInteger();

        private void resetConnectionCount() {
            connections.set(0);
        }

        private int connectionCount() {
            return connections.get();
        }

        private int openConnectionCount() {
            return openConnections.get();
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            Connection connection = delegate.connect(url, info);
            if (connection == null) return null;
            connections.incrementAndGet();
            openConnections.incrementAndGet();
            var closed = new AtomicBoolean();
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                try {
                                    Object result = method.invoke(connection, args);
                                    if (method.getName().equals("close")
                                            && closed.compareAndSet(false, true))
                                        openConnections.decrementAndGet();
                                    return result;
                                } catch (InvocationTargetException error) {
                                    throw error.getCause();
                                }
                            });
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
                throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}

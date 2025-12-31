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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for managing HttpTransport instances.
 *
 * <p>This factory provides:
 * <ul>
 *   <li>A lazily-initialized default HttpTransport singleton</li>
 *   <li>Registration of custom HttpTransport instances for lifecycle management</li>
 *   <li>Automatic cleanup via JVM shutdown hook</li>
 * </ul>
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Get the default transport (lazily created)
 * HttpTransport transport = HttpTransportFactory.getDefault();
 *
 * // Set a custom default transport
 * HttpTransport custom = OkHttpTransport.builder()
 *     .config(HttpTransportConfig.builder()
 *         .connectTimeout(Duration.ofSeconds(30))
 *         .build())
 *     .build();
 * HttpTransportFactory.setDefault(custom);
 *
 * // Register a transport for automatic cleanup
 * HttpTransportFactory.register(myTransport);
 * }</pre>
 *
 * <p>All registered transports and the default transport will be automatically
 * closed when the JVM shuts down.
 */
public final class HttpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(HttpTransportFactory.class);

    /** The default transport instance (lazily initialized). */
    private static volatile HttpTransport defaultTransport;

    /** List of all managed transports for cleanup. */
    private static final List<HttpTransport> managedTransports = new CopyOnWriteArrayList<>();

    /** Whether the shutdown hook has been registered. */
    private static volatile boolean shutdownHookRegistered = false;

    /** Lock object for synchronization. */
    private static final Object lock = new Object();

    private HttpTransportFactory() {
        // Utility class, no instantiation
    }

    /**
     * Get the default HttpTransport instance.
     *
     * <p>If no default has been set, a new OkHttpTransport with default configuration
     * will be created lazily. The default transport is automatically registered for
     * cleanup on JVM shutdown.
     *
     * @return the default HttpTransport instance
     */
    public static HttpTransport getDefault() {
        if (defaultTransport == null) {
            synchronized (lock) {
                if (defaultTransport == null) {
                    defaultTransport = new OkHttpTransport();
                    managedTransports.add(defaultTransport);
                    registerShutdownHookIfNeeded();
                    log.debug("Created default HttpTransport: {}", defaultTransport);
                }
            }
        }
        return defaultTransport;
    }

    /**
     * Set the default HttpTransport instance.
     *
     * <p>The provided transport will be registered for automatic cleanup if not already
     * registered. The previous default transport (if any) remains registered for cleanup.
     *
     * @param transport the transport to set as default (may be null to clear)
     */
    public static void setDefault(HttpTransport transport) {
        synchronized (lock) {
            defaultTransport = transport;
            if (transport != null && !managedTransports.contains(transport)) {
                managedTransports.add(transport);
            }
            registerShutdownHookIfNeeded();
            log.debug("Set default HttpTransport: {}", transport);
        }
    }

    /**
     * Register an HttpTransport for lifecycle management.
     *
     * <p>Registered transports will be automatically closed when the JVM shuts down.
     * This is useful for transports that are not set as the default but still need
     * cleanup.
     *
     * @param transport the transport to register
     */
    public static void register(HttpTransport transport) {
        if (transport != null && !managedTransports.contains(transport)) {
            managedTransports.add(transport);
            registerShutdownHookIfNeeded();
            log.debug("Registered HttpTransport for management: {}", transport);
        }
    }

    /**
     * Unregister an HttpTransport from lifecycle management.
     *
     * <p>After unregistration, the transport will not be closed during shutdown.
     * This does not close the transport immediately.
     *
     * @param transport the transport to unregister
     * @return true if the transport was unregistered, false if it was not registered
     */
    public static boolean unregister(HttpTransport transport) {
        boolean removed = managedTransports.remove(transport);
        if (removed) {
            log.debug("Unregistered HttpTransport from management: {}", transport);
        }
        return removed;
    }

    /**
     * Register the JVM shutdown hook if not already registered.
     */
    private static void registerShutdownHookIfNeeded() {
        if (!shutdownHookRegistered) {
            synchronized (lock) {
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime()
                            .addShutdownHook(
                                    new Thread(
                                            () -> {
                                                log.debug(
                                                        "Shutdown hook triggered, closing all"
                                                                + " HttpTransports");
                                                shutdown();
                                            },
                                            "HttpTransportFactory-ShutdownHook"));
                    shutdownHookRegistered = true;
                    log.debug("Registered HttpTransport shutdown hook");
                }
            }
        }
    }

    /**
     * Shutdown and close all managed transports.
     *
     * <p>This method is called automatically by the JVM shutdown hook, but can also
     * be called manually for testing or explicit cleanup. After calling this method,
     * the default transport will be null and all managed transports will be cleared.
     *
     * <p>Errors during close are logged but do not prevent other transports from
     * being closed.
     */
    public static void shutdown() {
        synchronized (lock) {
            log.info("Shutting down {} managed HttpTransport(s)", managedTransports.size());
            for (HttpTransport transport : managedTransports) {
                try {
                    transport.close();
                    log.debug("Closed HttpTransport: {}", transport);
                } catch (Exception e) {
                    log.warn("Error closing HttpTransport {}: {}", transport, e.getMessage());
                }
            }
            managedTransports.clear();
            defaultTransport = null;
        }
    }

    /**
     * Get the number of managed transports.
     *
     * <p>This method is primarily for testing purposes.
     *
     * @return the number of managed transports
     */
    public static int getManagedTransportCount() {
        return managedTransports.size();
    }

    /**
     * Check if a transport is currently managed.
     *
     * <p>This method is primarily for testing purposes.
     *
     * @param transport the transport to check
     * @return true if the transport is managed
     */
    public static boolean isManaged(HttpTransport transport) {
        return managedTransports.contains(transport);
    }
}

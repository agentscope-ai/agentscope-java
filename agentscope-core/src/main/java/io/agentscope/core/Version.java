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
package io.agentscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope version and User-Agent information.
 *
 * <p>Provides a unified User-Agent string for all model requests to identify AgentScope Java
 * clients and collect usage statistics.
 *
 * <p>The version is resolved at class-load time through a three-level fallback chain:
 *
 * <ol>
 *   <li>the {@code META-INF/agentscope/version.properties} classpath resource, whose
 *       {@code ${project.version}} placeholder is resolved by Maven resource filtering;
 *   <li>the {@code Implementation-Version} manifest entry of the containing jar, which is
 *       populated from {@code ${project.version}} by the Maven jar plugin;
 *   <li>the literal {@code "unknown"} as a last resort (e.g. running straight from IDE
 *       {@code target/classes} with no manifest), accompanied by a one-time warning log.
 * </ol>
 */
public final class Version {

    private static final Logger log = LoggerFactory.getLogger(Version.class);

    private static final String VERSION_RESOURCE = "/META-INF/agentscope/version.properties";

    /** Sentinel returned when no build-resolved version can be discovered. */
    static final String UNKNOWN = "unknown";

    /**
     * AgentScope Java version.
     *
     * <p>Injected at build time by Maven resource filtering from {@code ${project.version}};
     * see the class Javadoc for the fallback chain.
     */
    public static final String VERSION = resolveVersion();

    private Version() {
        // Utility class - prevent instantiation
    }

    private static String resolveVersion() {
        // 1) Maven-filtered classpath resource (normal Maven builds, tests).
        String fromResource;
        try (InputStream in = Version.class.getResourceAsStream(VERSION_RESOURCE)) {
            fromResource = resolveVersionFromResource(in);
        } catch (IOException e) {
            log.debug("Failed to read {} from the classpath", VERSION_RESOURCE, e);
            fromResource = UNKNOWN;
        }
        if (!UNKNOWN.equals(fromResource)) {
            return fromResource;
        }

        // 2) jar manifest Implementation-Version (packaged jars whose META-INF/agentscope
        //    directory may have been dropped, e.g. by shading or custom packaging).
        String fromManifest = resolveVersionFromManifest(getImplementationVersion());
        if (!UNKNOWN.equals(fromManifest)) {
            log.debug(
                    "Resolved version from jar manifest Implementation-Version: {}", fromManifest);
            return fromManifest;
        }

        // 3) Last resort: make the failure visible instead of silently using a stale value.
        log.warn(
                "Could not resolve the AgentScope version from {} or the jar manifest "
                        + "Implementation-Version; reporting '{}'. Run against a Maven-built "
                        + "classpath for accurate versioning.",
                VERSION_RESOURCE,
                UNKNOWN);
        return UNKNOWN;
    }

    /**
     * Load and resolve the version from the classpath resource stream.
     *
     * <p>Package-private for unit testing.
     *
     * @param in stream to {@code version.properties}, or {@code null} when the resource is absent
     * @return the resolved version, or {@code "unknown"} when the stream is {@code null} or cannot
     *     be read
     */
    static String resolveVersionFromResource(InputStream in) {
        if (in == null) {
            return UNKNOWN;
        }
        try (in) {
            Properties props = new Properties();
            props.load(in);
            return resolveVersionFrom(props);
        } catch (IOException e) {
            log.debug("Failed to read {}", VERSION_RESOURCE, e);
            return UNKNOWN;
        }
    }

    /**
     * Resolve the version string from a properties object, guarding against missing, blank,
     * and unfiltered ({@code ${...}}) values.
     *
     * <p>Package-private for unit testing.
     *
     * @param props properties loaded from the version resource
     * @return trimmed version, or {@code "unknown"} when absent, blank, or unfiltered
     */
    static String resolveVersionFrom(Properties props) {
        if (props == null) {
            return UNKNOWN;
        }
        String version = props.getProperty("version");
        // Guard against an unfiltered ${project.version} literal, e.g. when running
        // from an IDE that does not run Maven resource filtering.
        if (version != null && !version.isBlank() && !version.startsWith("${")) {
            return version.trim();
        }
        return UNKNOWN;
    }

    /**
     * Resolve the version from the jar manifest's {@code Implementation-Version} entry.
     *
     * <p>Package-private for unit testing.
     *
     * @param implementationVersion manifest value, or {@code null} when absent
     * @return the resolved version, or {@code "unknown"} when absent, blank, or unfiltered
     */
    static String resolveVersionFromManifest(String implementationVersion) {
        if (implementationVersion != null
                && !implementationVersion.isBlank()
                && !implementationVersion.startsWith("${")) {
            return implementationVersion.trim();
        }
        return UNKNOWN;
    }

    private static String getImplementationVersion() {
        Package pkg = Version.class.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
    }

    /**
     * Generate standard User-Agent string for all models.
     *
     * <p>Format: {@code agentscope-java/{version}; java/{java_version}; platform/{os}}
     *
     * @return unified User-Agent string
     */
    public static String getUserAgent() {
        return String.format(
                "agentscope-java/%s; java/%s; platform/%s",
                VERSION, System.getProperty("java.version"), System.getProperty("os.name"));
    }
}

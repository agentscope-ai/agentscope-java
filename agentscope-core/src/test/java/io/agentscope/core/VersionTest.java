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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Version} class.
 *
 * <p>Verifies the build-resolved version and User-Agent string generation for identifying
 * AgentScope Java clients.
 */
class VersionTest {

    private static final String MAIN_VERSION_RESOURCE = "/META-INF/agentscope/version.properties";
    private static final String TEST_VERSION_RESOURCE = "/agentscope-test-version.properties";
    private static final String SEMVER = "\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?";

    private static Properties loadProps(String resourcePath) throws IOException {
        try (InputStream in = VersionTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    @Test
    void testVersionConstant() throws IOException {
        Properties props = loadProps(MAIN_VERSION_RESOURCE);
        // The resource is absent only when the build did not run Maven resource processing at
        // all (e.g. an IDE run against raw target/classes without resources), so there is no
        // expected value to cross-check - skip, not fail.
        if (props == null) {
            Assumptions.abort(
                    MAIN_VERSION_RESOURCE
                            + " is missing from the classpath (non-Maven/IDE run);"
                            + " skipping strict version cross-check");
        }
        String expectedVersion = props.getProperty("version");
        Assertions.assertNotNull(expectedVersion, "version property should be present");

        // If the resource IS present but still contains the unfiltered ${project.version}
        // placeholder, Maven resource filtering genuinely did not apply. That is a loud
        // failure, not an assumption violation - a packaged jar would silently carry the
        // placeholder while Version.resolveVersionFrom masks it as "unknown".
        Assertions.assertFalse(
                expectedVersion.contains("${"),
                MAIN_VERSION_RESOURCE
                        + " is unfiltered (still contains '${'): Maven resource filtering"
                        + " did not apply");

        // Strict cross-check: the runtime version must match the Maven project version exactly.
        Assertions.assertEquals(
                expectedVersion, Version.VERSION, "VERSION must match the Maven project version");

        // Semantic version format check.
        Assertions.assertTrue(
                Version.VERSION.matches(SEMVER),
                "VERSION should be a valid semver: " + Version.VERSION);
    }

    @Test
    void testVersionConstant_CrossCheckTestResource() throws IOException {
        Properties props = loadProps(TEST_VERSION_RESOURCE);
        if (props == null) {
            Assumptions.abort(
                    TEST_VERSION_RESOURCE
                            + " is missing from the classpath (non-Maven/IDE run);"
                            + " skipping cross-check");
        }
        String expectedVersion = props.getProperty("version");
        Assertions.assertNotNull(expectedVersion, "version property should be present");
        Assertions.assertFalse(
                expectedVersion.contains("${"), "test version.properties is unfiltered");
        Assertions.assertEquals(
                expectedVersion,
                Version.VERSION,
                "test-resource version must match the runtime VERSION");
    }

    @Test
    void testResolveVersionFromResource_Normal() throws IOException {
        String content = "version=2.0.3-SNAPSHOT";
        Assertions.assertEquals(
                "2.0.3-SNAPSHOT",
                Version.resolveVersionFromResource(
                        new java.io.ByteArrayInputStream(
                                content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void testResolveVersionFromResource_NullStream() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromResource(null));
    }

    @Test
    void testResolveVersionFromResource_UnreadableStream() {
        InputStream broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("boom");
                    }
                };
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromResource(broken));
    }

    @Test
    void testResolveVersionFrom_Normal() {
        Properties props = new Properties();
        props.setProperty("version", "  2.0.3-SNAPSHOT  ");
        Assertions.assertEquals("2.0.3-SNAPSHOT", Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_UnfilteredPlaceholder() {
        Properties props = new Properties();
        props.setProperty("version", "${project.version}");
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_NullValue() {
        Properties props = new Properties();
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_BlankValue() {
        Properties props = new Properties();
        props.setProperty("version", "   ");
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(props));
    }

    @Test
    void testResolveVersionFrom_NullProps() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFrom(null));
    }

    @Test
    void testResolveVersionFromManifest_Normal() {
        Assertions.assertEquals(
                "2.0.3-SNAPSHOT", Version.resolveVersionFromManifest("2.0.3-SNAPSHOT"));
    }

    @Test
    void testResolveVersionFromManifest_Trimmed() {
        Assertions.assertEquals(
                "2.0.3-SNAPSHOT", Version.resolveVersionFromManifest("  2.0.3-SNAPSHOT  "));
    }

    @Test
    void testResolveVersionFromManifest_Null() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromManifest(null));
    }

    @Test
    void testResolveVersionFromManifest_Blank() {
        Assertions.assertEquals(Version.UNKNOWN, Version.resolveVersionFromManifest("   "));
    }

    @Test
    void testResolveVersionFromManifest_UnfilteredPlaceholder() {
        Assertions.assertEquals(
                Version.UNKNOWN, Version.resolveVersionFromManifest("${project.version}"));
    }

    @Test
    void testGetUserAgent_Format() {
        // Get User-Agent string
        String userAgent = Version.getUserAgent();

        // Verify not null/empty
        Assertions.assertNotNull(userAgent, "User-Agent should not be null");
        Assertions.assertFalse(userAgent.isEmpty(), "User-Agent should not be empty");

        // Verify format: agentscope-java/{version}; java/{java_version}; platform/{os}
        Assertions.assertTrue(
                userAgent.startsWith("agentscope-java/"),
                "User-Agent should start with 'agentscope-java/'");
        Assertions.assertTrue(userAgent.contains("; java/"), "User-Agent should contain '; java/'");
        Assertions.assertTrue(
                userAgent.contains("; platform/"), "User-Agent should contain '; platform/'");
    }

    @Test
    void testGetUserAgent_ContainsVersion() {
        String userAgent = Version.getUserAgent();

        // Verify contains AgentScope version
        Assertions.assertTrue(
                userAgent.contains(Version.VERSION),
                "User-Agent should contain AgentScope version: " + Version.VERSION);
    }

    @Test
    void testGetUserAgent_ContainsJavaVersion() {
        String userAgent = Version.getUserAgent();
        String javaVersion = System.getProperty("java.version");

        // Verify contains Java version
        Assertions.assertTrue(
                userAgent.contains(javaVersion),
                "User-Agent should contain Java version: " + javaVersion);
    }

    @Test
    void testGetUserAgent_ContainsPlatform() {
        String userAgent = Version.getUserAgent();
        String platform = System.getProperty("os.name");

        // Verify contains platform/OS name
        Assertions.assertTrue(
                userAgent.contains(platform), "User-Agent should contain platform: " + platform);
    }

    @Test
    void testGetUserAgent_Consistency() {
        // Verify multiple calls return the same value
        String userAgent1 = Version.getUserAgent();
        String userAgent2 = Version.getUserAgent();

        Assertions.assertEquals(
                userAgent1,
                userAgent2,
                "Multiple calls to getUserAgent() should return consistent results");
    }

    @Test
    void testGetUserAgent_ExampleFormat() {
        String userAgent = Version.getUserAgent();

        // Verify matches expected pattern (relaxed check for different environments)
        String pattern = "^agentscope-java/.+; java/[0-9.]+; platform/.+$";
        Assertions.assertTrue(
                userAgent.matches(pattern),
                "User-Agent should match pattern: " + pattern + ", but got: " + userAgent);
    }
}

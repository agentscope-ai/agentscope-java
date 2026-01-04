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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BailianConfigTest {

    @Test
    void testBuilderWithAllRequiredFields() {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .workspaceId("test-workspace")
                        .build();

        assertEquals("test-key-id", config.getAccessKeyId());
        assertEquals("test-key-secret", config.getAccessKeySecret());
        assertEquals("test-workspace", config.getWorkspaceId());
        assertEquals("bailian.cn-beijing.aliyuncs.com", config.getEndpoint());
        assertNull(config.getIndexId());
    }

    @Test
    void testBuilderWithAllFields() {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .workspaceId("test-workspace")
                        .indexId("test-index")
                        .endpoint("custom.endpoint.com")
                        .build();

        assertEquals("test-key-id", config.getAccessKeyId());
        assertEquals("test-key-secret", config.getAccessKeySecret());
        assertEquals("test-workspace", config.getWorkspaceId());
        assertEquals("test-index", config.getIndexId());
        assertEquals("custom.endpoint.com", config.getEndpoint());
    }

    @Test
    void testBuilderMissingAccessKeyId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianConfig.builder()
                                .accessKeySecret("test-key-secret")
                                .workspaceId("test-workspace")
                                .build());
    }

    @Test
    void testBuilderMissingAccessKeySecret() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianConfig.builder()
                                .accessKeyId("test-key-id")
                                .workspaceId("test-workspace")
                                .build());
    }

    @Test
    void testBuilderMissingWorkspaceId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianConfig.builder()
                                .accessKeyId("test-key-id")
                                .accessKeySecret("test-key-secret")
                                .build());
    }

    @Test
    void testBuilderEmptyAccessKeyId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianConfig.builder()
                                .accessKeyId("   ")
                                .accessKeySecret("test-key-secret")
                                .workspaceId("test-workspace")
                                .build());
    }

    @Test
    void testBuilderEmptyAccessKeySecret() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianConfig.builder()
                                .accessKeyId("test-key-id")
                                .accessKeySecret("")
                                .workspaceId("test-workspace")
                                .build());
    }

    @Test
    void testBuilderEmptyWorkspaceId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BailianConfig.builder()
                                .accessKeyId("test-key-id")
                                .accessKeySecret("test-key-secret")
                                .workspaceId("   ")
                                .build());
    }

    @Test
    void testDefaultEndpoint() {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .workspaceId("test-workspace")
                        .build();

        assertEquals("bailian.cn-beijing.aliyuncs.com", config.getEndpoint());
    }

    @Test
    void testCustomEndpoints() {
        // Test Finance Cloud endpoint
        BailianConfig financeConfig =
                BailianConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .workspaceId("test-workspace")
                        .endpoint("bailian.cn-shanghai-finance-1.aliyuncs.com")
                        .build();

        assertEquals("bailian.cn-shanghai-finance-1.aliyuncs.com", financeConfig.getEndpoint());

        // Test VPC endpoint
        BailianConfig vpcConfig =
                BailianConfig.builder()
                        .accessKeyId("test-key-id")
                        .accessKeySecret("test-key-secret")
                        .workspaceId("test-workspace")
                        .endpoint("bailian-vpc.cn-beijing.aliyuncs.com")
                        .build();

        assertEquals("bailian-vpc.cn-beijing.aliyuncs.com", vpcConfig.getEndpoint());
    }
}

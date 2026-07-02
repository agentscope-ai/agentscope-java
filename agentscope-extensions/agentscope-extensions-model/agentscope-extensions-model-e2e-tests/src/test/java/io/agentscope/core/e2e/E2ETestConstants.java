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
package io.agentscope.core.e2e;

/**
 * Constants for E2E tests including test data URLs and common values.
 *
 * <p>All test images and videos are hosted on Aliyun OSS for reliable access during testing.
 */
public final class E2ETestConstants {

    private E2ETestConstants() {
        // Prevent instantiation
    }

    // ==========================================================================
    // Test Image URLs
    // ==========================================================================

    /** Cat image for vision tests. */
    public static final String CAT_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

    /** Dog and girl image for vision tests. */
    public static final String DOG_GIRL_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/dog_and_girl.png";

    // ==========================================================================
    // Test Video URLs
    // ==========================================================================

    /** Test video for video analysis tests. */
    public static final String TEST_VIDEO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

    // ==========================================================================
    // Test Timeouts
    // ==========================================================================

    /** Default timeout for E2E tests in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** Short timeout for simple operations in seconds. */
    public static final int SHORT_TIMEOUT_SECONDS = 30;

    /** Long timeout for complex operations in seconds. */
    public static final int LONG_TIMEOUT_SECONDS = 300;
}

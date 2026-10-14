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
package io.agentscope.harness.agent.context;

import io.agentscope.core.message.ContentBlock;

/**
 * Estimates the token cost of a single media block for context budgeting.
 *
 * <p>Image, audio, video and data payloads are counted far higher than a plain text block because
 * providers charge media at a resolution- and duration-dependent rate. The default constant is a
 * conservative upper bound so the trigger and final budget checks agree; providers that expose an
 * exact media cost can supply their own estimator through {@link TokenAccountingService}.
 */
@FunctionalInterface
public interface MediaTokenEstimator {

    /** Conservative default cost in tokens for one media block. */
    long DEFAULT_MEDIA_TOKENS = 2048;

    long estimate(ContentBlock block);

    /** Stable identifier of the estimation method, recorded alongside the surfaced estimate. */
    default String method() {
        return "constant-media-" + DEFAULT_MEDIA_TOKENS + "-v1";
    }

    static MediaTokenEstimator defaults() {
        return block -> DEFAULT_MEDIA_TOKENS;
    }
}

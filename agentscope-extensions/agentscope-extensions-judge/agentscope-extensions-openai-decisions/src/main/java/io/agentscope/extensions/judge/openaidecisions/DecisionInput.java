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

package io.agentscope.extensions.judge.openaidecisions;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Text and/or images, encoded as text or a single user message. No images are fetched locally. */
public record DecisionInput(String text, List<String> imageUrls) {
    public DecisionInput {
        imageUrls = List.copyOf(imageUrls);
        if (text == null && imageUrls.isEmpty()) {
            throw new IllegalArgumentException("text or at least one image is required");
        }
        if (imageUrls.size() > 128) {
            throw new IllegalArgumentException("at most 128 images are allowed");
        }
        for (String image : imageUrls) {
            if (image.startsWith("data:image/")
                    && image.contains(";base64,")
                    && !image.endsWith(";base64,")) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(image);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "image must be an image data URL or http(s) URL");
            }
            if (!("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "image must be an image data URL or http(s) URL");
            }
        }
    }

    public static DecisionInput text(String text) {
        return new DecisionInput(Objects.requireNonNull(text, "text"), List.of());
    }

    public static DecisionInput image(String imageUrl) {
        return new DecisionInput(null, List.of(imageUrl));
    }
}

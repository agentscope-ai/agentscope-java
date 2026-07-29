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
package io.agentscope.builder.web.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ApiExceptionTest {

    @Test
    void detailFromWrapsApiException() {
        ApiException ex =
                ApiException.invalidRequest("unknown_event_type", "bad type", "events[].type");
        ApiErrorDetail detail = ApiException.detailFrom(ex);
        assertThat(detail.type()).isEqualTo(ApiErrorType.INVALID_REQUEST.wireName());
        assertThat(detail.code()).isEqualTo("unknown_event_type");
        assertThat(detail.message()).isEqualTo("bad type");
        assertThat(detail.param()).isEqualTo("events[].type");
    }

    @Test
    void detailFromMapsBareResponseStatusException() {
        ResponseStatusException ex =
                new ResponseStatusException(HttpStatus.NOT_FOUND, "missing resource");
        ApiErrorDetail detail = ApiException.detailFrom(ex);
        assertThat(detail.type()).isEqualTo(ApiErrorType.NOT_FOUND.wireName());
        assertThat(detail.code()).isEqualTo("http_404");
        assertThat(detail.message()).isEqualTo("missing resource");
    }

    @Test
    void fromHttpStatusMapsCategories() {
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.BAD_REQUEST))
                .isEqualTo(ApiErrorType.INVALID_REQUEST);
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.UNAUTHORIZED))
                .isEqualTo(ApiErrorType.AUTHENTICATION);
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.FORBIDDEN))
                .isEqualTo(ApiErrorType.PERMISSION);
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.NOT_FOUND))
                .isEqualTo(ApiErrorType.NOT_FOUND);
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.CONFLICT))
                .isEqualTo(ApiErrorType.CONFLICT);
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.TOO_MANY_REQUESTS))
                .isEqualTo(ApiErrorType.RATE_LIMIT);
        assertThat(ApiErrorType.fromHttpStatus(HttpStatus.INTERNAL_SERVER_ERROR))
                .isEqualTo(ApiErrorType.API);
        assertThat(ApiErrorType.fromHttpStatus(null)).isEqualTo(ApiErrorType.API);
    }
}

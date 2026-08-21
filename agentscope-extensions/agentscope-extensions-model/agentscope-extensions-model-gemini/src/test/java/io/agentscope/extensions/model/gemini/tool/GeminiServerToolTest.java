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
package io.agentscope.extensions.model.gemini.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.AuthConfig;
import com.google.genai.types.GoogleMaps;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.ImageSearch;
import com.google.genai.types.Interval;
import com.google.genai.types.PhishBlockThreshold;
import com.google.genai.types.SearchTypes;
import com.google.genai.types.Tool;
import com.google.genai.types.WebSearch;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiServerToolTest {

    @Test
    void mapsGoogleSearchParams() {
        SearchTypes searchTypes =
                SearchTypes.builder()
                        .webSearch(WebSearch.builder())
                        .imageSearch(ImageSearch.builder())
                        .build();
        Interval interval =
                Interval.builder()
                        .startTime(Instant.parse("2026-07-01T00:00:00Z"))
                        .endTime(Instant.parse("2026-07-31T23:59:59Z"))
                        .build();

        Tool tool =
                GeminiServerTool.googleSearch()
                        .params(
                                Map.of(
                                        "searchTypes",
                                        searchTypes,
                                        "blockingConfidence",
                                        "BLOCK_HIGH_AND_ABOVE",
                                        "excludeDomains",
                                        List.of("example.com", "example.org"),
                                        "timeRangeFilter",
                                        interval))
                        .build()
                        .toTool();

        GoogleSearch googleSearch = tool.googleSearch().orElseThrow();
        assertEquals(searchTypes, googleSearch.searchTypes().orElseThrow());
        assertEquals(
                PhishBlockThreshold.Known.BLOCK_HIGH_AND_ABOVE,
                googleSearch.blockingConfidence().orElseThrow().knownEnum());
        assertEquals(
                List.of("example.com", "example.org"), googleSearch.excludeDomains().orElseThrow());
        assertEquals(interval, googleSearch.timeRangeFilter().orElseThrow());
    }

    @Test
    void mapsGoogleMapsParams() {
        AuthConfig authConfig = AuthConfig.builder().apiKey("maps-key").build();

        Tool tool =
                GeminiServerTool.googleMap()
                        .param("authConfig", authConfig)
                        .param("enableWidget", true)
                        .build()
                        .toTool();

        GoogleMaps googleMaps = tool.googleMaps().orElseThrow();
        assertEquals(authConfig, googleMaps.authConfig().orElseThrow());
        assertTrue(googleMaps.enableWidget().orElseThrow());
    }

    @Test
    void rejectsUnsupportedParamForToolType() {
        GeminiServerTool serverTool =
                GeminiServerTool.googleSearch().param("enableWidget", true).build();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, serverTool::toTool);

        assertTrue(error.getMessage().contains("enableWidget"));
        assertTrue(error.getMessage().contains(GeminiServerTool.GOOGLE_SEARCH));
    }

    @Test
    void rejectsParamWithWrongType() {
        GeminiServerTool serverTool =
                GeminiServerTool.googleSearch().param("excludeDomains", "example.com").build();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, serverTool::toTool);

        assertTrue(error.getMessage().contains("excludeDomains"));
        assertTrue(error.getMessage().contains("List"));
    }

    @Test
    void rejectsIncompleteSearchTimeRange() {
        Interval interval =
                Interval.builder().startTime(Instant.parse("2026-07-01T00:00:00Z")).build();
        GeminiServerTool serverTool =
                GeminiServerTool.googleSearch().param("timeRangeFilter", interval).build();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, serverTool::toTool);

        assertTrue(error.getMessage().contains("timeRangeFilter"));
        assertTrue(error.getMessage().contains("startTime and endTime"));
    }

    @Test
    void rejectsMissingToolType() {
        GeminiServerTool serverTool = GeminiServerTool.builder().build();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, serverTool::toTool);

        assertTrue(error.getMessage().contains("Unknown tool type"));
    }

    @Test
    void copiesParamsWhenBuilt() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("excludeDomains", List.of("example.com"));
        GeminiServerTool serverTool = GeminiServerTool.googleSearch().params(params).build();

        params.put("unsupported", true);

        assertEquals(
                List.of("example.com"),
                serverTool.toTool().googleSearch().orElseThrow().excludeDomains().orElseThrow());
    }
}

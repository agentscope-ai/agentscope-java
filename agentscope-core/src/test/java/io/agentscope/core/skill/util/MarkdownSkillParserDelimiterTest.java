/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class MarkdownSkillParserDelimiterTest {

    @ParameterizedTest(name = "{0}, line ending/BOM variant {index}")
    @MethodSource("yamlMarkerCases")
    void markersInsideYamlAreNotClosingDelimiters(
            String label,
            String yaml,
            Map<String, Object> expectedMetadata,
            String lineEnding,
            String bom) {
        String normalizedYaml = yaml.replace("\n", lineEnding);
        assertEquals(expectedMetadata, loadYaml(normalizedYaml), "YAML fixture: " + label);
        String body = "# Instructions" + lineEnding + "Keep the body exactly.";

        ParsedMarkdown parsed =
                MarkdownSkillParser.parse(
                        bom + "---" + lineEnding + normalizedYaml + "---" + lineEnding + body);

        assertParsed(expectedMetadata, body, parsed);
    }

    private static Stream<Arguments> yamlMarkerCases() {
        return Stream.of(
                        new String[] {"plain", "description: Use --- safely.\n", "Use --- safely."},
                        new String[] {
                            "single quoted", "description: 'Use --- safely.'\n", "Use --- safely."
                        },
                        new String[] {
                            "double quoted", "description: \"Use --- safely.\"\n", "Use --- safely."
                        },
                        new String[] {
                            "literal",
                            "description: |-\n  Use --- safely.\n  Keep this line.\n",
                            "Use --- safely.\nKeep this line."
                        },
                        new String[] {
                            "folded",
                            "description: >-\n  Use --- safely.\n  Keep this line.\n",
                            "Use --- safely. Keep this line."
                        },
                        new String[] {
                            "literal indented marker",
                            "description: |-\n  Before\n  ---\n  After\n",
                            "Before\n---\nAfter"
                        },
                        new String[] {
                            "folded indented marker",
                            "description: >-\n  Before\n  ---\n  After\n",
                            "Before --- After"
                        },
                        new String[] {
                            "comment", "description: Keep this.\n# --- in a comment\n", "Keep this."
                        },
                        new String[] {
                            "marker prefix key",
                            "description: Keep this.\n---suffix: preserved\n",
                            "Keep this."
                        })
                .flatMap(
                        fixture -> {
                            String yaml =
                                    "name: delimiter_test\n"
                                            + fixture[1]
                                            + "version: '1.0'\n"
                                            + "metadata:\n  enabled: true\n  tags: [one, two]\n";
                            Map<String, Object> expected = metadata(fixture[2]);
                            if (fixture[0].equals("marker prefix key")) {
                                expected.put("---suffix", "preserved");
                            }
                            return lineEndingsAndBom()
                                    .map(
                                            variant ->
                                                    Arguments.of(
                                                            fixture[0],
                                                            yaml,
                                                            expected,
                                                            variant[0],
                                                            variant[1]));
                        });
    }

    @ParameterizedTest
    @MethodSource("documentVariants")
    void missingStandaloneClosingDelimiterPreservesTheEntireDocument(
            String lineEnding, String bom) {
        String yaml =
                String.join(
                        lineEnding,
                        "name: delimiter_test",
                        "description: Use --- safely.",
                        "---suffix: preserved");
        assertEquals(
                Map.of(
                        "name", "delimiter_test",
                        "description", "Use --- safely.",
                        "---suffix", "preserved"),
                loadYaml(yaml));
        String markdown = bom + "---" + lineEnding + yaml;

        assertParsed(Map.of(), markdown, MarkdownSkillParser.parse(markdown));
    }

    @ParameterizedTest
    @MethodSource("documentVariants")
    void preservesDelimiterWhitespaceAndStopsAtTheFirstClosingLine(String lineEnding, String bom) {
        String body = String.join(lineEnding, "# Body", "---", "later: body", "---");
        String markdown =
                bom
                        + "--- \t"
                        + lineEnding
                        + lineEnding
                        + "name: boundary"
                        + lineEnding
                        + lineEnding
                        + "--- \t"
                        + lineEnding
                        + " \t"
                        + lineEnding
                        + lineEnding
                        + body;

        assertParsed(Map.of("name", "boundary"), body, MarkdownSkillParser.parse(markdown));
    }

    @ParameterizedTest
    @MethodSource("documentVariants")
    void closingDelimiterAtEofPreservesTrailingBodyWhitespace(String lineEnding, String bom) {
        for (String suffix : List.of("", " \t")) {
            String markdown =
                    bom + "---" + lineEnding + "name: boundary" + lineEnding + "---" + suffix;

            assertParsed(Map.of("name", "boundary"), suffix, MarkdownSkillParser.parse(markdown));
        }
    }

    @ParameterizedTest
    @MethodSource("documentVariants")
    void emptyMetadataStillConsumesTheDelimiterLines(String lineEnding, String bom) {
        String markdown =
                bom + "--- \t" + lineEnding + lineEnding + "--- \t" + lineEnding + "# Body";

        assertParsed(Map.of(), "# Body", MarkdownSkillParser.parse(markdown));
    }

    @ParameterizedTest
    @MethodSource("documentVariants")
    void preservesFencedAndIndentedBodyCodeAndPlainMarkdown(String lineEnding, String bom) {
        String body =
                String.join(
                        lineEnding,
                        "```markdown",
                        "---",
                        "name: body-only",
                        "---",
                        "```",
                        "",
                        "    ---",
                        "    indented body",
                        "");
        String markdown =
                bom
                        + "---"
                        + lineEnding
                        + "name: boundary"
                        + lineEnding
                        + "---"
                        + lineEnding
                        + body;

        assertParsed(Map.of("name", "boundary"), body, MarkdownSkillParser.parse(markdown));
        assertParsed(Map.of(), bom + body, MarkdownSkillParser.parse(bom + body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void malformedYamlFallbackKeepsInlineMarkers(String lineEnding) {
        String yaml =
                String.join(
                        lineEnding,
                        "name: boundary",
                        "description: Use --- safely.",
                        "broken: [unclosed",
                        "version: '1.0'");
        assertThrows(RuntimeException.class, () -> loadYaml(yaml));
        String markdown = "---" + lineEnding + yaml + lineEnding + "---" + lineEnding + "# Body";

        assertParsed(
                Map.of(
                        "name", "boundary",
                        "description", "Use --- safely.",
                        "broken", "[unclosed",
                        "version", "1.0"),
                "# Body",
                MarkdownSkillParser.parse(markdown));
    }

    @Test
    void generatedMetadataContainingMarkersRoundTripsWithExactBody() {
        Map<String, Object> expected = metadata("Use --- safely.");
        expected.put("notes", "Before\n---\nAfter");
        String body = "# Body\n---\nKeep this horizontal rule.\n";
        String generated = MarkdownSkillParser.generate(expected, body);

        ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

        assertParsed(expected, body, parsed);
        assertEquals(
                generated, MarkdownSkillParser.generate(parsed.getMetadata(), parsed.getContent()));
    }

    private static Map<String, Object> metadata(String description) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "delimiter_test");
        metadata.put("description", description);
        metadata.put("version", "1.0");
        metadata.put("metadata", Map.of("enabled", true, "tags", List.of("one", "two")));
        return metadata;
    }

    private static Stream<String[]> lineEndingsAndBom() {
        return Stream.of("\n", "\r\n", "\r")
                .flatMap(
                        lineEnding ->
                                Stream.of("", "\uFEFF").map(bom -> new String[] {lineEnding, bom}));
    }

    private static Stream<Arguments> documentVariants() {
        return lineEndingsAndBom().map(variant -> Arguments.of(variant[0], variant[1]));
    }

    private static Object loadYaml(String yaml) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    private static void assertParsed(
            Map<String, Object> metadata, String body, ParsedMarkdown parsed) {
        assertAll(
                () -> assertEquals(metadata, parsed.getMetadata(), "complete metadata"),
                () -> assertEquals(body, parsed.getContent(), "exact body"));
    }
}

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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImagePayloadTransformerTest {

    private static final String PNG_DATA = "aW1hZ2UtcGF5bG9hZA==";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void offloadAndHydratePreserveMessagesWithoutMutatingInput() throws Exception {
        ChatUsage usage = new ChatUsage(11, 7, 3, 0.25);
        ImageBlock image = image("image/png", PNG_DATA, 128, 2048);
        DataBlock data =
                DataBlock.builder()
                        .source(new Base64Source("image/png", PNG_DATA))
                        .id("data-1")
                        .name("diagram.png")
                        .build();
        UserMessage user =
                UserMessage.builder()
                        .id("user-1")
                        .name("alice")
                        .content(List.of(image, data))
                        .metadata(Map.of("trace", "t-1"))
                        .timestamp("2026-08-27 10:00:00.000")
                        .usage(usage)
                        .build();
        Msg base =
                new Msg(
                        "base-1",
                        "legacy",
                        MsgRole.USER,
                        List.of(image),
                        Map.of("legacy", true),
                        null,
                        usage);

        ImagePayloadTransformer.OffloadResult result =
                ImagePayloadTransformer.offload(List.of(user, base));

        assertEquals(1, result.payloads().size(), "identical images should be deduplicated");
        assertEquals(PNG_DATA, ((Base64Source) image.getSource()).getData());

        Msg strippedUser = result.messages().get(0);
        Msg strippedBase = result.messages().get(1);
        assertInstanceOf(UserMessage.class, strippedUser);
        assertEquals(Msg.class, strippedBase.getClass());
        assertNotSame(user, strippedUser);
        assertNotSame(base, strippedBase);
        assertMessageFields(user, strippedUser);
        assertMessageFields(base, strippedBase);
        assertNull(strippedBase.getTimestamp());

        ImageBlock strippedImage = (ImageBlock) strippedUser.getContent().get(0);
        DataBlock strippedData = (DataBlock) strippedUser.getContent().get(1);
        String imageReference = ((Base64Source) strippedImage.getSource()).getData();
        String dataReference = ((Base64Source) strippedData.getSource()).getData();
        assertEquals(imageReference, dataReference);
        assertEquals(1, ImagePayloadTransformer.referencedPayloadIds(result.messages()).size());
        assertTrue(
                imageReference.matches("^__agentscope_image_ref__:v1:[0-9a-f]{64}$"),
                imageReference);
        assertEquals(128, strippedImage.getMinPixels());
        assertEquals(2048, strippedImage.getMaxPixels());
        assertEquals("data-1", strippedData.getId());
        assertEquals("diagram.png", strippedData.getName());
        assertFalse(
                JsonUtils.getJsonCodec().toJson(result.messages()).contains(PNG_DATA),
                "lightweight messages must not contain inline image data");

        List<Msg> hydrated = ImagePayloadTransformer.hydrate(result.messages(), result.payloads());
        assertEquals(
                mapper.readTree(mapper.writeValueAsString(List.of(user, base))),
                mapper.readTree(mapper.writeValueAsString(hydrated)));
        assertInstanceOf(UserMessage.class, hydrated.get(0));
        assertEquals(Msg.class, hydrated.get(1).getClass());
    }

    @Test
    void recursivelyTransformsNestedToolResultsAndPreservesFields() throws Exception {
        ImageBlock nestedImage = image("image/jpeg", "bmVzdGVkLWltYWdl", 32, 4096);
        ToolResultBlock inner =
                new ToolResultBlock(
                        "inner-id",
                        "inner-tool",
                        List.of(nestedImage),
                        Map.of("inner", 1),
                        ToolResultState.SUCCESS);
        ToolResultBlock outer =
                new ToolResultBlock(
                        "outer-id",
                        "outer-tool",
                        List.of(TextBlock.builder().text("before").build(), inner),
                        Map.of("outer", 2),
                        ToolResultState.ERROR);
        ToolResultMessage message =
                ToolResultMessage.builder()
                        .id("tool-message")
                        .name("runner")
                        .content(outer)
                        .metadata(Map.of("message", "metadata"))
                        .timestamp("2026-08-27 11:00:00.000")
                        .build();

        ImagePayloadTransformer.OffloadResult result =
                ImagePayloadTransformer.offload(List.of(message));

        assertEquals(1, result.payloads().size());
        ToolResultMessage stripped =
                assertInstanceOf(ToolResultMessage.class, result.messages().get(0));
        ToolResultBlock strippedOuter =
                assertInstanceOf(ToolResultBlock.class, stripped.getContent().get(0));
        ToolResultBlock strippedInner =
                assertInstanceOf(ToolResultBlock.class, strippedOuter.getOutput().get(1));
        assertToolResultFields(outer, strippedOuter);
        assertToolResultFields(inner, strippedInner);
        assertTrue(
                ((Base64Source) ((ImageBlock) strippedInner.getOutput().get(0)).getSource())
                        .getData()
                        .startsWith(ImagePayloadTransformer.REFERENCE_PREFIX));

        List<Msg> hydrated = ImagePayloadTransformer.hydrate(result.messages(), result.payloads());
        assertEquals(
                mapper.readTree(mapper.writeValueAsString(List.of(message))),
                mapper.readTree(mapper.writeValueAsString(hydrated)));
    }

    @Test
    void leavesUrlsAndNonImageDataUntouchedAndMatchesImageMimeCaseInsensitively() {
        ImageBlock urlImage =
                ImageBlock.builder().source(new URLSource("https://example.com/image.png")).build();
        DataBlock audio =
                DataBlock.builder()
                        .source(new Base64Source("audio/wav", "YXVkaW8="))
                        .id("audio-1")
                        .build();
        DataBlock nonCanonicalMime =
                DataBlock.builder()
                        .source(new Base64Source("Image/PNG", PNG_DATA))
                        .id("data-2")
                        .build();
        UserMessage message =
                UserMessage.builder().content(List.of(urlImage, audio, nonCanonicalMime)).build();

        ImagePayloadTransformer.OffloadResult result =
                ImagePayloadTransformer.offload(List.of(message));

        assertEquals(1, result.payloads().size());
        Msg lightweight = result.messages().get(0);
        assertSame(urlImage, lightweight.getContent().get(0));
        assertSame(audio, lightweight.getContent().get(1));
        assertTrue(
                ((Base64Source) ((DataBlock) lightweight.getContent().get(2)).getSource())
                        .getData()
                        .startsWith(ImagePayloadTransformer.REFERENCE_PREFIX));
        List<Msg> hydrated = ImagePayloadTransformer.hydrate(result.messages(), result.payloads());
        assertEquals(
                PNG_DATA,
                ((Base64Source) ((DataBlock) hydrated.get(0).getContent().get(2)).getSource())
                        .getData());
    }

    @Test
    void hashesMimeTypeAndPayloadAndMakesRepeatedOffloadIdempotent() {
        UserMessage message =
                UserMessage.builder()
                        .content(
                                List.of(
                                        image("image/png", PNG_DATA, null, null),
                                        image("image/jpeg", PNG_DATA, null, null)))
                        .build();

        ImagePayloadTransformer.OffloadResult first =
                ImagePayloadTransformer.offload(List.of(message));
        assertEquals(2, first.payloads().size());
        String firstReference =
                ((Base64Source)
                                ((ImageBlock) first.messages().get(0).getContent().get(0))
                                        .getSource())
                        .getData();
        String secondReference =
                ((Base64Source)
                                ((ImageBlock) first.messages().get(0).getContent().get(1))
                                        .getSource())
                        .getData();
        assertNotEquals(firstReference, secondReference);

        ImagePayloadTransformer.OffloadResult second =
                ImagePayloadTransformer.offload(first.messages());
        assertTrue(second.payloads().isEmpty());
        assertSame(first.messages().get(0), second.messages().get(0));
    }

    @Test
    void rejectsMalformedMissingAndTamperedPayloads() {
        UserMessage inline =
                UserMessage.builder().content(image("image/png", PNG_DATA, null, null)).build();
        ImagePayloadTransformer.OffloadResult result =
                ImagePayloadTransformer.offload(List.of(inline));
        String payloadId = result.payloads().keySet().iterator().next();

        assertThrows(
                IllegalStateException.class,
                () -> ImagePayloadTransformer.hydrate(result.messages(), Map.of()));
        assertThrows(
                IllegalStateException.class,
                () ->
                        ImagePayloadTransformer.hydrate(
                                result.messages(),
                                Map.of(
                                        payloadId,
                                        new ImagePayloadState("image/png", "dGFtcGVyZWQ="))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        ImagePayloadTransformer.hydrate(
                                result.messages(),
                                Map.of(payloadId, new ImagePayloadState("image/jpeg", PNG_DATA))));

        UserMessage malformed =
                UserMessage.builder()
                        .content(
                                image(
                                        "image/png",
                                        "__agentscope_image_ref__:v2:" + "a".repeat(64),
                                        null,
                                        null))
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> ImagePayloadTransformer.offload(List.of(malformed)));
    }

    @Test
    void imagePayloadStateRoundTripsThroughJson() throws Exception {
        ImagePayloadState payload = new ImagePayloadState("image/png", PNG_DATA);

        String json = mapper.writeValueAsString(payload);
        ImagePayloadState restored = mapper.readValue(json, ImagePayloadState.class);

        assertEquals(payload, restored);
        assertTrue(json.contains("\"media_type\":\"image/png\""), json);
    }

    @Test
    void customMsgSubtypeCanPreserveItsTypeWhenContentNeedsTransformation() {
        Msg custom =
                new CustomMessage("custom-id", List.of(image("image/png", PNG_DATA, null, null)));

        ImagePayloadTransformer.OffloadResult offloaded =
                ImagePayloadTransformer.offload(List.of(custom));

        assertInstanceOf(CustomMessage.class, offloaded.messages().get(0));
        assertInstanceOf(
                CustomMessage.class,
                ImagePayloadTransformer.hydrate(offloaded.messages(), offloaded.payloads()).get(0));
    }

    private static ImageBlock image(
            String mediaType, String data, Integer minPixels, Integer maxPixels) {
        return ImageBlock.builder()
                .source(new Base64Source(mediaType, data))
                .minPixels(minPixels)
                .maxPixels(maxPixels)
                .build();
    }

    private static void assertMessageFields(Msg expected, Msg actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getRole(), actual.getRole());
        assertEquals(expected.getMetadata(), actual.getMetadata());
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertSame(expected.getUsage(), actual.getUsage());
    }

    private static void assertToolResultFields(ToolResultBlock expected, ToolResultBlock actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getMetadata(), actual.getMetadata());
        assertEquals(expected.getState(), actual.getState());
    }

    private static final class CustomMessage extends Msg {

        private CustomMessage(String id, List<ContentBlock> content) {
            super(id, "custom", MsgRole.USER, content, Map.of(), null, null);
        }

        @Override
        protected Msg copyWithContent(List<ContentBlock> newContent) {
            return new CustomMessage(getId(), newContent);
        }
    }
}

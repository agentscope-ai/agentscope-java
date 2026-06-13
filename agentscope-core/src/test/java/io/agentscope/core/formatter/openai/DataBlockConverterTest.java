package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataBlockConverterTest {

    @Test
    void testDataBlockImageUrlNotDropped() {
        var formatter = new OpenAIChatFormatter();

        var msg =
                new UserMessage(
                        List.of(
                                TextBlock.builder().text("analyze this").build(),
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/photo.png")
                                                        .build())
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(
                content instanceof List<?>,
                "expected List<OpenAIContentPart>, got " + content.getClass());

        List<?> rawParts = (List<?>) content;
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) rawParts;

        boolean hasText =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "text".equals(p.getType())
                                                && "analyze this".equals(p.getText()));
        boolean hasImage =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "image_url".equals(p.getType())
                                                && p.getImageUrl() != null
                                                && "https://example.com/photo.png"
                                                        .equals(p.getImageUrl().getUrl()));

        assertTrue(hasText, "text block should be present in multimodal message");
        assertTrue(hasImage, "image_url from DataBlock should be present in multimodal message");
    }

    @Test
    void testDataBlockBase64Image() {
        var formatter = new OpenAIChatFormatter();

        var msg =
                new UserMessage(
                        List.of(
                                DataBlock.builder()
                                        .source(new Base64Source("image/png", "iVBORw0KGgo="))
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(
                content instanceof List<?>,
                "expected List<OpenAIContentPart>, got " + content.getClass());

        List<?> rawParts = (List<?>) content;
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) rawParts;

        boolean hasImage =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "image_url".equals(p.getType())
                                                && p.getImageUrl() != null
                                                && p.getImageUrl()
                                                        .getUrl()
                                                        .startsWith("data:image/png;base64"));

        assertTrue(hasImage, "base64 image from DataBlock should be present");
    }
}

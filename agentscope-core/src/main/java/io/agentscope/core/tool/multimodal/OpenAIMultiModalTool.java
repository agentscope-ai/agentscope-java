/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.multimodal;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.models.audio.speech.SpeechCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.images.Image;
import com.openai.models.images.ImageCreateVariationParams;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * openai multimodal tool.
 * convert text to image(s), edit image, create image variation, convert images to text, convert text to audio, and convert audio to text.
 * Please refer to the <a href="https://platform.openai.com/">`openai documentation`</a> for more details.
 */
public class OpenAIMultiModalTool {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMultiModalTool.class);

    /**
     * OpenAI API key.
     */
    private final String apiKey;

    /**
     * OpenAI client.
     */
    private OpenAIClient client;

    public OpenAIMultiModalTool(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("openai API key cannot be empty.");
        }
        this.apiKey = apiKey;
        this.client =
                OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .putHeader("User-Agent", Version.getUserAgent())
                        .build();
    }

    /**
     * Generate image(s) based on the given prompt, and return image url(s) or base64 data.
     *
     * @param prompt         The text prompt to generate image
     * @param model          The model to use for image generation, e.g., 'dall-e-2', 'dall-e-3', etc.
     * @param n              The number of images to generate
     * @param size           The size of the generated images.
     *                       Must be one of 1024x1024, 1536x1024 (landscape), 1024x1536 (portrait), or auto (default value) for gpt-image-1,
     *                       one of 256x256, 512x512, or 1024x1024 for dall-e-2,
     *                       and one of 1024x1024, 1792x1024, or 1024x1792 for dall-e-3.
     * @param quality        The quality of the image that will be generated.
     *                       <ul>
     *                       <li>auto` (default value) will automatically select the best quality for the given model.</li>
     *                       <li>`high`, `medium` and `low` are supported for gpt-image-1.</li>
     *                       <li>`hd` and `standard` are supported for dall-e-3.</li>
     *                       <li>`standard` is the only option for dall-e-2.</li>
     *                       </ul>
     * @param style          The style of the generated images. This parameter is only supported for dall-e-3. Must be one of `vivid` or `natural`.
     *                       <ul>
     *                       <li>`Vivid` causes the model to lean towards generating hyper-real and dramatic images.</li>
     *                       <li>`Natural` causes the model to produce more natural, less hyper-real looking images.</li>
     *                       </ul>
     * @param responseFormat The format in which generated images with dall-e-2 and dall-e-3  are returned.
     *                       <ul>
     *                       <li>Must be one of "url" or "b64_json".</li>
     *                       <li>URLs are only valid for 60 minutes after the image has been generated.</li>
     *                       <li>This parameter isn't supported for gpt-image-1 which will always return base64-encoded images.</li>
     *                       </ul>
     * @return A ToolResultBlock containing the generated image url, base64 data, or error message.
     */
    @Tool(
            name = "openai_text_to_image",
            description =
                    "Generate image(s) based on the given prompt, and return image url(s) or base64"
                            + " data.")
    public Mono<ToolResultBlock> openaiTextToImage(
            @ToolParam(name = "prompt", description = "The text prompt to generate image")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use for image generation, e.g., 'dall-e-2',"
                                            + " 'dall-e-3', etc.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "n",
                            description = "The number of images to generate",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description = "The size of the generated images.",
                            required = false)
                    String size,
            @ToolParam(
                            name = "quality",
                            description = "The quality of the image that will be generated.",
                            required = false)
                    String quality,
            @ToolParam(
                            name = "style",
                            description =
                                    "The style of the generated images.This parameter is only"
                                            + " supported for dall-e-3.Must be one of `vivid` or"
                                            + " `natural`.",
                            required = false)
                    String style,
            @ToolParam(
                            name = "response_format",
                            description =
                                    "The format in which generated images with dall-e-2 and"
                                            + " dall-e-3 are returned.",
                            required = false)
                    String responseFormat) {

        Integer finalN = Optional.ofNullable(n).orElse(1);
        String finalSize =
                Optional.ofNullable(size)
                        .filter(
                                s ->
                                        List.of(
                                                        "256x256",
                                                        "512x512",
                                                        "1024x1024",
                                                        "1792x1024",
                                                        "1024x1792")
                                                .contains(s))
                        .orElse("256x256");
        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> List.of("dall-e-2", "dall-e-3", "gpt-image-1").contains(s))
                        .orElse("dall-e-2");
        String finalQuality =
                Optional.ofNullable(quality)
                        .filter(
                                s ->
                                        List.of("auto", "standard", "hd", "high", "medium", "low")
                                                .contains(s))
                        .orElse("auto");
        String finalStyle =
                Optional.ofNullable(style)
                        .filter(s -> List.of("vivid", "natural").contains(s))
                        .orElse("vivid");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat)
                        .filter(s -> List.of("url", "b64_json").contains(s))
                        .orElse("url");

        log.debug(
                "openai_text_to_image called: prompt='{}', n='{}', size='{}', model='{}',"
                        + " quality='{}', style='{}', response_format='{}'",
                prompt,
                finalN,
                finalSize,
                finalModel,
                finalQuality,
                finalStyle,
                finalResponseFormat);

        return Mono.fromCallable(
                        () -> {
                            ImageGenerateParams params =
                                    ImageGenerateParams.builder()
                                            .prompt(prompt)
                                            .n(finalN)
                                            .size(ImageGenerateParams.Size.of(finalSize))
                                            .model(finalModel)
                                            .quality(ImageGenerateParams.Quality.of(finalQuality))
                                            .style(ImageGenerateParams.Style.of(finalStyle))
                                            .responseFormat(
                                                    ImageGenerateParams.ResponseFormat.of(
                                                            finalResponseFormat))
                                            .build();
                            ImagesResponse response = client.images().generate(params);

                            List<Image> images = response.data().orElse(null);
                            if (images == null || images.isEmpty()) {
                                log.error("No image url returned.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (Image image : images) {
                                if (Objects.equals(finalResponseFormat, "url")) {
                                    String url = image.url().orElse(null);
                                    if (url == null || url.trim().isEmpty()) {
                                        log.error("Generate image url is empty.");
                                        return ToolResultBlock.error("Failed to generate images.");
                                    }

                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(URLSource.builder().url(url).build())
                                                    .build());
                                } else {
                                    String data = image.b64Json().orElse(null);
                                    if (data == null || data.trim().isEmpty()) {
                                        log.error("Generate image base64 data is empty.");
                                        return ToolResultBlock.error("Failed to generate images.");
                                    }

                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .mediaType("image/png")
                                                                    .data(data)
                                                                    .build())
                                                    .build());
                                }
                            }
                            return ToolResultBlock.of(contentBlocks);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate images '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Edit an image based on the provided mask and prompt, and return the edited
     * image URL(s) or base64 data.
     *
     * @param imageUrl       The file path or URL to the image that needs editing.
     * @param prompt         The text prompt describing the edits to be made to the image.
     * @param model          The model to use for image generation. Must be one of 'dall-e-2' or 'gpt-image-1'.
     * @param maskUrl        The file path or URL to the mask image that specifies the regions to be edited.
     * @param n              The number of edited images to generate.
     * @param size           The size of the edited images. Must be one of "256x256", "512x512", or "1024x1024".
     * @param responseFormat The format in which generated images are returned.
     *                       <ul>
     *                       <li>Must be one of "url" or "b64_json".</li>
     *                       <li>URLs are only valid for 60 minutes after generation.</li>
     *                       <li>This parameter isn't supported for gpt-image-1 which will always return base64-encoded images.</li>
     *                       </ul>
     * @return A ToolResultBlock containing the edited image url, base64 data, or error message.
     */
    @Tool(
            name = "openai_edit_image",
            description =
                    "Edit an image based on the provided mask and prompt, and return the edited"
                            + " image URL(s) or base64 data.")
    public Mono<ToolResultBlock> openaiEditImage(
            @ToolParam(
                            name = "image_url",
                            description = "The file path or URL to the image that needs editing.")
                    String imageUrl,
            @ToolParam(
                            name = "prompt",
                            description =
                                    "The text prompt describing the edits to be made to the image.")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use for image generation. Must be one of"
                                            + " 'dall-e-2' or 'gpt-image-1'.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "mask_url",
                            description =
                                    "The file path or URL to the mask image that specifies the"
                                            + " regions to be edited.",
                            required = false)
                    String maskUrl,
            @ToolParam(
                            name = "n",
                            description = "The number of edited images to generate.",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description =
                                    "The size of the edited images. Must be one of '256x256',"
                                            + " '512x512', or '1024x1024'.",
                            required = false)
                    String size,
            @ToolParam(
                            name = "response_format",
                            description = "The format in which generated images are returned.",
                            required = false)
                    String responseFormat) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("dall-e-2");
        Integer finalN = Optional.ofNullable(n).orElse(1);
        String finalSize =
                Optional.ofNullable(size)
                        .filter(s -> List.of("256x256", "512x512", "1024x1024").contains(s))
                        .orElse("256x256");
        String finalResponseFormat =
                "dall-e-2".equals(finalModel)
                        ? Optional.ofNullable(responseFormat)
                                .filter(s -> List.of("url", "b64_json").contains(s))
                                .orElse("url")
                        : "b64_json";

        log.debug(
                "openai_edit_image called: image_url='{}', prompt='{}', model='{}', maskUrl='{}',"
                        + " n='{}', size='{}', response_format='{}'",
                imageUrl,
                prompt,
                finalModel,
                maskUrl,
                finalN,
                finalSize,
                finalResponseFormat);

        return Mono.fromCallable(
                        () -> {
                            ImageEditParams.Builder paramsBuilder =
                                    ImageEditParams.builder()
                                            .model(finalModel)
                                            .image(MediaUtils.urlToRgbaImageInputStream(imageUrl))
                                            .prompt(prompt)
                                            .n(finalN)
                                            .size(ImageEditParams.Size.of(finalSize))
                                            .responseFormat(
                                                    ImageEditParams.ResponseFormat.of(
                                                            finalResponseFormat));
                            if (maskUrl != null) {
                                paramsBuilder.mask(MediaUtils.urlToRgbaImageInputStream(maskUrl));
                            }

                            ImagesResponse response = client.images().edit(paramsBuilder.build());

                            List<Image> images = response.data().orElse(null);
                            if (images == null || images.isEmpty()) {
                                log.error("No edited image returned.");
                                return ToolResultBlock.error("Failed to edit image.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (Image image : images) {
                                if ("url".equals(finalResponseFormat)) {
                                    String url = image.url().orElse(null);
                                    if (url == null || url.trim().isEmpty()) {
                                        log.error("Edited image URL is empty.");
                                        return ToolResultBlock.error("Failed to edit image.");
                                    }
                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(URLSource.builder().url(url).build())
                                                    .build());
                                } else {
                                    String data = image.b64Json().orElse(null);
                                    if (data == null || data.trim().isEmpty()) {
                                        log.error("Edited image base64 data is empty.");
                                        return ToolResultBlock.error("Failed to edit image.");
                                    }
                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .mediaType("image/png")
                                                                    .data(data)
                                                                    .build())
                                                    .build());
                                }
                            }
                            return ToolResultBlock.of(contentBlocks);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to edit image: {}", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Create variations of an image and return the image URL(s) or base64 data.
     *
     * @param imageUrl       The file path or URL to the image from which variations will be generated.
     * @param model          The model to use for image variation.
     * @param n              The number of image variations to generate.
     * @param size           The size of the generated image variations.
     * @param responseFormat The format in which generated images are returned.
     *                       <ul>
     *                       <li>Must be one of "url" or "b64_json".</li>
     *                       <li>URLs are only valid for 60 minutes after the image has been generated.</li>
     *                       </ul>
     * @return A ToolResultBlock containing the created variation image url, base64 data, or error message.
     */
    @Tool(
            name = "openai_create_image_variation",
            description =
                    "Create variations of an image and return the image URL(s) or base64 data.")
    public Mono<ToolResultBlock> openaiCreateImageVariation(
            @ToolParam(name = "imageUrl", description = "The file path or URL to the image.")
                    String imageUrl,
            @ToolParam(
                            name = "model",
                            description = "The model to use for image generation, e.g., dall-e-2. ",
                            required = false)
                    String model,
            @ToolParam(
                            name = "n",
                            description = "The number of image variations to generate.",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description = "The size of the generated images.",
                            required = false)
                    String size,
            @ToolParam(
                            name = "responseFormat",
                            description = "The format in which generated images are returned.",
                            required = false)
                    String responseFormat) {

        final String finalModel = Optional.ofNullable(model).orElse("dall-e-2");
        final Integer finalN = Optional.ofNullable(n).orElse(1);
        final String finalSize = Optional.ofNullable(size).orElse("256x256");
        final String finalResponseFormat = Optional.ofNullable(responseFormat).orElse("url");

        log.debug(
                "openai_create_image_variation called: imageUrl='{}', n='{}', model='{}',"
                        + " size='{}', response_format='{}'",
                imageUrl,
                finalN,
                finalModel,
                finalSize,
                finalResponseFormat);

        return Mono.fromCallable(
                        () -> {
                            ImageCreateVariationParams params =
                                    ImageCreateVariationParams.builder()
                                            .model(finalModel)
                                            .image(MediaUtils.urlToRgbaImageInputStream(imageUrl))
                                            .n(finalN)
                                            .size(ImageCreateVariationParams.Size.of(finalSize))
                                            .build();

                            ImagesResponse response = client.images().createVariation(params);

                            List<Image> images = response.data().orElse(null);
                            if (images == null || images.isEmpty()) {
                                log.error("No image variation returned.");
                                return ToolResultBlock.error("Failed to create image variation.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (Image image : images) {
                                if ("url".equals(finalResponseFormat)) {
                                    String url = image.url().orElse(null);
                                    if (url == null || url.trim().isEmpty()) {
                                        log.error("Generated image URL is empty.");
                                        return ToolResultBlock.error(
                                                "Failed to create image variation.");
                                    }
                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(URLSource.builder().url(url).build())
                                                    .build());
                                } else {
                                    String b64Data = image.b64Json().orElse(null);
                                    if (b64Data == null || b64Data.trim().isEmpty()) {
                                        log.error("Generated image base64 data is empty.");
                                        return ToolResultBlock.error(
                                                "Failed to create image variation.");
                                    }
                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .mediaType("image/png")
                                                                    .data(b64Data)
                                                                    .build())
                                                    .build());
                                }
                            }
                            return ToolResultBlock.of(contentBlocks);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to create image variation: {}", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Generate descriptive text for given image(s) using a specified model, and return the generated text.
     *
     * @param imageUrls The URL or list of URLs pointing to the images that need to be described.
     * @param prompt    The prompt that instructs the model on how to describe the image(s).
     * @param model     The model to use for generating the text descriptions.
     * @return A ToolResultBlock containing the generated text or error message.
     */
    public Mono<ToolResultBlock> openaiImageToText(
            @ToolParam(
                            name = "imageUrls",
                            description = "The URL or list of URLs pointing to the images.")
                    List<String> imageUrls,
            @ToolParam(
                            name = "prompt",
                            description = "The prompt that instructs the model.",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description = "The model to use for generating the text descriptions.",
                            required = false)
                    String model) {

        final String finalPrompt = Optional.ofNullable(prompt).orElse("Describe the image");
        final String finalModel = Optional.ofNullable(model).orElse("gpt-4o");

        log.debug(
                "openai_image_to_text called: imageUrls='{}', prompt='{}', model='{}'",
                imageUrls,
                finalPrompt,
                finalModel);

        return Mono.fromCallable(
                        () -> {
                            List<ChatCompletionContentPart> parts = new ArrayList<>();
                            for (String url : imageUrls) {
                                String base64DataUrl;
                                try {
                                    base64DataUrl = MediaUtils.urlToBase64DataUrl(url);
                                } catch (IOException e) {
                                    log.error(
                                            "Failed to convert url to base64 data url, {}",
                                            e.getMessage(),
                                            e);
                                    return ToolResultBlock.error(e.getMessage());
                                }
                                parts.add(
                                        ChatCompletionContentPart.ofImageUrl(
                                                ChatCompletionContentPartImage.builder()
                                                        .imageUrl(
                                                                ChatCompletionContentPartImage
                                                                        .ImageUrl.builder()
                                                                        .url(base64DataUrl)
                                                                        .build())
                                                        .build()));
                            }
                            ChatCompletionUserMessageParam userMessageParam =
                                    ChatCompletionUserMessageParam.builder()
                                            .contentOfArrayOfContentParts(parts)
                                            .content(
                                                    ChatCompletionContentPartText.builder()
                                                            .text(finalPrompt)
                                                            .build()
                                                            .text())
                                            .build();

                            List<ChatCompletionMessageParam> messages = new ArrayList<>();
                            messages.add(ChatCompletionMessageParam.ofUser(userMessageParam));

                            ChatCompletionCreateParams params =
                                    ChatCompletionCreateParams.builder()
                                            .messages(messages)
                                            .model(finalModel)
                                            .build();

                            ChatCompletion chatCompletion =
                                    client.chat().completions().create(params);

                            String text =
                                    Optional.ofNullable(chatCompletion)
                                            .map(ChatCompletion::choices)
                                            .flatMap(choices -> choices.stream().findFirst())
                                            .map(ChatCompletion.Choice::message)
                                            .flatMap(ChatCompletionMessage::content)
                                            .orElse(null);
                            if (text == null || text.trim().isEmpty()) {
                                log.error("No generated text returned.");
                                return ToolResultBlock.error("Failed to generate text.");
                            }

                            return ToolResultBlock.of(TextBlock.builder().text(text).build());
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate text: {}", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Convert text to an audio file using a specified model and voice.
     *
     * @param text      The text to convert to audio.
     * @param model     The model to use for text-to-speech conversion.
     * @param voice     The voice to use for the audio output.
     * @param speed     The speed of the audio playback. A value of 1.0 is normal speed.
     * @param resFormat The format of the audio file.
     * @return A ToolResultBlock containing the base64 data of audio file or error message.
     */
    @Tool(
            name = "openai_text_to_audio",
            description = "Convert text to an audio file using a specified model and voice.")
    public Mono<ToolResultBlock> openaiTextToAudio(
            @ToolParam(name = "text", description = "The text to convert to audio.") String text,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use for text-to-speech conversion, e.g., 'tts-1',"
                                            + " 'tts-1-hd', 'gpt-4o-mini-tts'.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "voice",
                            description = "The voice to use for the audio output.",
                            required = false)
                    String voice,
            @ToolParam(
                            name = "speed",
                            description = "The speed of the audio playback.",
                            required = false)
                    Float speed,
            @ToolParam(
                            name = "resFormat",
                            description = "The format of the audio file.",
                            required = false)
                    String resFormat) {

        final String finalModel = Optional.ofNullable(model).orElse("tts-1");
        final String finalVoice = Optional.ofNullable(voice).orElse("alloy");
        final float finalSpeed = Optional.ofNullable(speed).orElse(1.0f);
        final String finalResFormat = Optional.ofNullable(resFormat).orElse("mp3");

        log.debug(
                "openai_text_to_audio called: text='{}', model='{}', voice='{}', speed='{}',"
                        + " resFormat='{}'",
                text,
                finalModel,
                finalVoice,
                finalSpeed,
                finalResFormat);

        return Mono.fromCallable(
                        () -> {
                            SpeechCreateParams params =
                                    SpeechCreateParams.builder()
                                            .model(finalModel)
                                            .voice(finalVoice)
                                            .speed(finalSpeed)
                                            .input(text)
                                            .responseFormat(
                                                    SpeechCreateParams.ResponseFormat.of(
                                                            finalResFormat))
                                            .build();

                            try (HttpResponse response = client.audio().speech().create(params)) {
                                if (response == null || response.body() == null) {
                                    log.error("No response body returned.");
                                    return ToolResultBlock.error("Failed to generate audio.");
                                }
                                String data =
                                        Base64.getEncoder()
                                                .encodeToString(response.body().readAllBytes());
                                return ToolResultBlock.of(
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType(
                                                                        "audio/" + finalResFormat)
                                                                .data(data)
                                                                .build())
                                                .build());
                            }
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate audio: {}", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Convert an audio file to text using OpenAI's transcription service (Whisper).
     *
     * @param audioFileUrl The file path or URL to the audio file that needs to be transcribed.
     * @param model        The model to use for audio transcription.
     * @param language     ISO-639-1 language code, e.g., 'en', 'zh', 'fr'. Improves accuracy and latency.
     * @param temperature  The temperature for the transcription, which affects the  randomness of the output.
     * @return A ToolResultBlock containing the transcribed text or error message.
     */
    @Tool(
            name = "openai_audio_to_text",
            description = "Convert an audio file to text using OpenAI's transcription service.")
    public Mono<ToolResultBlock> openaiAudioToText(
            @ToolParam(
                            name = "audio_file_url",
                            description =
                                    "The file path or URL to the audio file that needs to be"
                                            + " transcribed.")
                    String audioFileUrl,
            @ToolParam(
                            name = "model",
                            description = "The model to use for audio transcription.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "language",
                            description =
                                    "ISO-639-1 language code, e.g., 'en', 'zh', 'fr'. Improves"
                                            + " accuracy and latency.",
                            required = false)
                    String language,
            @ToolParam(
                            name = "temperature",
                            description =
                                    " The temperature for the transcription, which affects the "
                                            + " randomness of the output.",
                            required = false)
                    Float temperature) {

        final String finalModel = Optional.ofNullable(model).orElse("whisper-1");
        final String finalLanguage = Optional.ofNullable(language).orElse("en");
        final float finalTemperature = Optional.ofNullable(temperature).orElse(0.2f);

        log.debug(
                "openai_audio_to_text called: audioFileUrl='{}', model='{}', language='{}',"
                        + " temperature='{}'",
                audioFileUrl,
                finalModel,
                finalLanguage,
                finalTemperature);

        return Mono.fromCallable(
                        () -> {
                            TranscriptionCreateParams params =
                                    TranscriptionCreateParams.builder()
                                            .model(finalModel)
                                            .file(MediaUtils.urlToInputStream(audioFileUrl))
                                            .language(finalLanguage)
                                            .temperature(finalTemperature)
                                            .build();

                            TranscriptionCreateResponse response =
                                    client.audio().transcriptions().create(params);

                            String transcript = response.asTranscription().text();
                            if (transcript == null || transcript.trim().isEmpty()) {
                                log.error("No generated text returned");
                                return ToolResultBlock.error("Failed to transcribe audio.");
                            }
                            return ToolResultBlock.of(TextBlock.builder().text(transcript).build());
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to transcribe audio: {}", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }
}

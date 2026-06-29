package com.pixflow.module.vision.image;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.op.ConvertFormatSpec;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.ResizeSpec;
import com.pixflow.infra.image.op.impl.ConvertFormatOp;
import com.pixflow.infra.image.op.impl.ResizeOp;
import com.pixflow.infra.image.pipeline.ImagePipeline;
import com.pixflow.module.vision.config.VisionProperties;
import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class VisionImagePreprocessor {
    private static final Color WHITE = Color.WHITE;

    private final ImagePipeline imagePipeline;
    private final VisionProperties properties;

    public VisionImagePreprocessor(ImagePipeline imagePipeline, VisionProperties properties) {
        this.imagePipeline = Objects.requireNonNull(imagePipeline, "imagePipeline");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public PreparedVisionImage preprocess(ResolvedVisionImage image) {
        Objects.requireNonNull(image, "image");
        try (var stream = image.stream()) {
            VisionProperties.Image imageProps = properties.getImage();
            List<ImageOp> ops = List.of(
                    new ResizeOp(new ResizeSpec(imageProps.getMaxLongEdge(), imageProps.getMaxLongEdge(), ResizeSpec.Mode.FIT, false)),
                    new ConvertFormatOp(new ConvertFormatSpec(
                            imageProps.getOutputFormat(),
                            imageProps.jpegQualityPercent(),
                            flattenBackground(imageProps))));
            byte[] encoded = imagePipeline.run(
                    stream,
                    ops,
                    new EncodeSpec(imageProps.getOutputFormat(), imageProps.jpegQualityPercent(), null, flattenBackground(imageProps)));
            String contentType = "image/" + imageProps.getOutputFormat().writerName().toLowerCase(Locale.ROOT);
            ChatMessage.ImagePart part = new ChatMessage.ImagePart(
                    new ChatMessage.BytesImageContent(encoded, contentType),
                    description(image.ref()));
            return new PreparedVisionImage(image.ref(), part, image.sizeBytes(), encoded.length);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to close image stream", ex);
        }
    }

    private Color flattenBackground(VisionProperties.Image imageProps) {
        return imageProps.getTransparentBackground() == VisionProperties.TransparentBackground.WHITE ? WHITE : null;
    }

    private String description(com.pixflow.module.vision.analyze.VisionImageRef ref) {
        if (ref.hintLabel() != null) {
            return ref.hintLabel();
        }
        if (ref.viewId() != null) {
            return "view=" + ref.viewId();
        }
        return null;
    }
}

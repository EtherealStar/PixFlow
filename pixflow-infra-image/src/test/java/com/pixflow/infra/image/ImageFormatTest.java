package com.pixflow.infra.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageFormatTest {

    @Test
    void exposesDecodeEncodeAndAlphaCapabilities() {
        assertThat(ImageFormat.WEBP.canDecode()).isTrue();
        assertThat(ImageFormat.WEBP.canEncode()).isTrue();
        assertThat(ImageFormat.WEBP.supportsAlpha()).isTrue();
        assertThat(ImageFormat.TIFF.canDecode()).isTrue();
        assertThat(ImageFormat.TIFF.canEncode()).isFalse();
        assertThat(ImageFormat.JPEG.supportsAlpha()).isFalse();
    }

    @Test
    void resolvesCommonAliases() {
        assertThat(ImageFormat.fromName("jpg")).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.fromName("tif")).contains(ImageFormat.TIFF);
        assertThat(ImageFormat.fromName("png")).contains(ImageFormat.PNG);
        assertThat(ImageFormat.fromName(" PNG ")).contains(ImageFormat.PNG);
    }
}

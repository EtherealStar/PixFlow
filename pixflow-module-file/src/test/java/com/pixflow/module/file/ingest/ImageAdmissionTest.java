package com.pixflow.module.file.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.file.config.FileProperties;
import org.junit.jupiter.api.Test;

class ImageAdmissionTest {
    private final ImageAdmission admission = new ImageAdmission(new FileProperties());

    @Test
    void acceptsPngMagicBytes() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        ImageAdmission.AdmissionResult result = admission.inspect("a.png", png, png.length);

        assertThat(result.accepted()).isTrue();
        assertThat(result.contentType()).isEqualTo("image/png");
    }

    @Test
    void rejectsDisguisedJpg() {
        byte[] text = "not an image".getBytes();

        ImageAdmission.AdmissionResult result = admission.inspect("a.jpg", text, text.length);

        assertThat(result.accepted()).isFalse();
        assertThat(result.code()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT");
    }
}

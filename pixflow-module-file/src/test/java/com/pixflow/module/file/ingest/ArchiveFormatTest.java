package com.pixflow.module.file.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import org.junit.jupiter.api.Test;

class ArchiveFormatTest {
    @Test
    void detectsAllSupportedArchiveSignatures() {
        assertThat(ArchiveFormat.detect(new byte[]{0x50, 0x4b, 0x03, 0x04})).isEqualTo(ArchiveFormat.ZIP);
        assertThat(ArchiveFormat.detect(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01}))
                .isEqualTo(ArchiveFormat.RAR);
        assertThat(ArchiveFormat.detect(new byte[]{0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c}))
                .isEqualTo(ArchiveFormat.SEVEN_Z);
    }

    @Test
    void rejectsExtensionAndSignatureOutsideTheProtocol() {
        assertThatThrownBy(() -> ArchiveFormat.fromFilename("materials.tar"))
                .isInstanceOf(PixFlowException.class);
        assertThatThrownBy(() -> ArchiveFormat.detect(new byte[]{1, 2, 3}))
                .isInstanceOf(PixFlowException.class);
    }
}

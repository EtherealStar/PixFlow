package com.pixflow.module.file.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import org.junit.jupiter.api.Test;

class ZipPathValidatorTest {

    @Test
    void normalizesSafeRelativePath() {
        assertThat(ZipPathValidator.validate("images\\sku_1.png")).isEqualTo("images/sku_1.png");
    }

    @Test
    void rejectsTraversal() {
        assertThatThrownBy(() -> ZipPathValidator.validate("../evil.png"))
                .isInstanceOf(PixFlowException.class);
    }

    @Test
    void rejectsWindowsDrivePath() {
        assertThatThrownBy(() -> ZipPathValidator.validate("C:/evil.png"))
                .isInstanceOf(PixFlowException.class);
    }
}

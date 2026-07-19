package com.pixflow.module.imagegen.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImagegenPlanValidatorTest {

    private SourceImageReader reader;

    private ImagegenPlanValidator validator;

    @BeforeEach
    void setUp() {
        reader = referenceKey -> Optional.of(new SourceImageInfo(referenceKey, "image/png"));
        validator = new ImagegenPlanValidator(new ImagegenProperties(), reader);
    }

    @Test
    void validatesOneConcreteImageReference() {
        ImagegenPlan plan = validator.validate(new ImagegenPlanInputs(
                "package:7/image:11", "  重绘  ", "备注", Map.of("style", "A")), "conv-1");

        assertThat(plan.sourceReferenceKey()).isEqualTo("package:7/image:11");
        assertThat(plan.packageId()).isEqualTo(7L);
        assertThat(plan.prompt()).isEqualTo("重绘");
        assertThat(plan.params()).containsOnlyKeys("style");
    }

    @Test
    void rejectsPackageAndSkuReferencesAtTheImagegenBoundary() {
        assertThatThrownBy(() -> validator.validate(new ImagegenPlanInputs(
                "package:7", "重绘", null, Map.of()), "conv-1"))
                .isInstanceOf(PixFlowException.class)
                .extracting(error -> ((PixFlowException) error).code())
                .isEqualTo(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND);
        assertThatThrownBy(() -> validator.validate(new ImagegenPlanInputs(
                "package:7/sku:SKU-1", "重绘", null, Map.of()), "conv-1"))
                .isInstanceOf(PixFlowException.class);
    }

    @Test
    void rejectsNonCanonicalReferenceBeforeReadingSourceFacts() {
        ImagegenPlanValidator isolated = new ImagegenPlanValidator(
                new ImagegenProperties(), referenceKey -> {
                    throw new AssertionError("source facts must not be read");
                });

        assertThatThrownBy(() -> isolated.validate(new ImagegenPlanInputs(
                "package:07/image:11", "重绘", null, Map.of()), "conv-1"))
                .isInstanceOf(PixFlowException.class);
    }

    @Test
    void rejectsMissingSourceAndUnsupportedType() {
        ImagegenPlanValidator missing = new ImagegenPlanValidator(
                new ImagegenProperties(), referenceKey -> Optional.empty());
        assertThatThrownBy(() -> missing.validate(new ImagegenPlanInputs(
                "package:7/image:11", "重绘", null, Map.of()), "conv-1"))
                .isInstanceOf(PixFlowException.class)
                .extracting(error -> ((PixFlowException) error).code())
                .isEqualTo(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND);

        ImagegenPlanValidator unsupported = new ImagegenPlanValidator(
                new ImagegenProperties(), referenceKey -> Optional.of(new SourceImageInfo(
                        referenceKey, "image/gif")));
        assertThatThrownBy(() -> unsupported.validate(new ImagegenPlanInputs(
                "package:7/image:11", "重绘", null, Map.of()), "conv-1"))
                .isInstanceOf(PixFlowException.class)
                .extracting(error -> ((PixFlowException) error).code())
                .isEqualTo(ImagegenErrorCode.IMAGEGEN_UNSUPPORTED_SOURCE_TYPE);
    }

    @Test
    void rejectsBlankPromptAndUnknownParameters() {
        assertThatThrownBy(() -> validator.validate(new ImagegenPlanInputs(
                "package:7/image:11", " ", null, Map.of()), "conv-1"))
                .isInstanceOf(PixFlowException.class);
        assertThatThrownBy(() -> validator.validate(new ImagegenPlanInputs(
                "package:7/image:11", "重绘", null, Map.of("secret", "value")), "conv-1"))
                .isInstanceOf(PixFlowException.class);
    }
}

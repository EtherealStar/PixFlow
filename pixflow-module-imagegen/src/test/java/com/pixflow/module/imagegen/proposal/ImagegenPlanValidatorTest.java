package com.pixflow.module.imagegen.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenPlanValidator 单测(对齐 imagegen.md §十五)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常路径(去重 + 字典序归一 + 白名单归一)</li>
 *   <li>10 条 ImagegenErrorCode 中 validator 负责的 5 条(IMAGEGEN_SOURCE_IMAGE_NOT_FOUND / _NOT_IN_PACKAGE / _PROMPT_INVALID / _TOO_MANY_SOURCES / _UNSUPPORTED_SOURCE_TYPE)</li>
 * </ul>
 */
class ImagegenPlanValidatorTest {

    private static final String CONV = "conv-1";
    private static final String PKG = "pkg-1";

    private ImagegenProperties properties;
    private FakeSourceImageReader reader;
    private ImagegenPlanValidator validator;

    @BeforeEach
    void setUp() {
        properties = new ImagegenProperties();
        reader = new FakeSourceImageReader();
        validator = new ImagegenPlanValidator(properties, reader);
    }

    @Test
    @DisplayName("正常路径:去重 + 字典序归一 + 白名单归一")
    void validate_happyPath() {
        reader.byId.put("img-1", info("img-1"));
        reader.byId.put("img-2", info("img-2"));
        ImagegenPlanInputs inputs = inputs(List.of("img-2", "img-1", "img-1"),
            "  A 风格重绘  ",
            Map.of("style", "A", "strength", 0.6),
            "用户偏好测试");

        ImagegenPlan plan = validator.validate(inputs, CONV, PKG);

        // 字典序
        assertThat(plan.sourceImageIds()).containsExactly("img-1", "img-2");
        // trim
        assertThat(plan.prompt()).isEqualTo("A 风格重绘");
        // 白名单内
        assertThat(plan.params()).containsOnlyKeys("style", "strength");
        // note 原样保留
        assertThat(plan.note()).isEqualTo("用户偏好测试");
        // 会话上下文
        assertThat(plan.conversationId()).isEqualTo(CONV);
        assertThat(plan.packageId()).isEqualTo(PKG);
        // reader 收到了去重后的 imageId 集(顺序由调用方决定:此处为输入顺序)
        assertThat(reader.lastCalledIds()).containsExactlyInAnyOrder("img-1", "img-2");
    }

    @Test
    @DisplayName("source_image_ids 为空 → IMAGEGEN_SOURCE_IMAGE_NOT_FOUND")
    void validate_emptySourceImageIds_throws() {
        ImagegenPlanInputs inputs = inputs(List.of(), "重绘", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("source_image_ids 含不存在 imageId → IMAGEGEN_SOURCE_IMAGE_NOT_FOUND")
    void validate_missingSourceImage_throws() {
        reader.byId.clear();
        reader.byId.put("img-1", info("img-1"));
        // 不放 img-2
        ImagegenPlanInputs inputs = inputs(List.of("img-1", "img-2"), "重绘", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("源图不归属 packageId → IMAGEGEN_SOURCE_NOT_IN_PACKAGE")
    void validate_wrongPackage_throws() {
        reader.byId.clear();
        reader.byId.put("img-1", new SourceImageInfo("img-1", "pkg-other", "sku-1", "key/1", "image/png", null, null));
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), "重绘", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_SOURCE_NOT_IN_PACKAGE);
    }

    @Test
    @DisplayName("prompt 空 → IMAGEGEN_PROMPT_INVALID")
    void validate_blankPrompt_throws() {
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), " ", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID);
    }

    @Test
    @DisplayName("prompt 超 max-length → IMAGEGEN_PROMPT_INVALID")
    void validate_longPrompt_throws() {
        String longPrompt = "x".repeat(properties.getProposal().getPromptMaxChars() + 1);
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), longPrompt, Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID);
    }

    @Test
    @DisplayName("params 含白名单外键 → IMAGEGEN_PROMPT_INVALID")
    void validate_unknownParamKey_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("style", "A");
        params.put("secrets", "ak-xxx");
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), "重绘", params, null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID);
    }

    @Test
    @DisplayName("source_image_ids 超 max-source-images → IMAGEGEN_TOO_MANY_SOURCES")
    void validate_tooManySourceImages_throws() {
        // 配置成 3 张,送 4 张
        properties.getProposal().setMaxSourceImages(3);
        List<String> ids = List.of("a", "b", "c", "d");
        reader.byId.clear();
        for (String id : ids) {
            reader.byId.put(id, info(id));
        }
        ImagegenPlanInputs inputs = inputs(ids, "重绘", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_TOO_MANY_SOURCES);
    }

    @Test
    @DisplayName("源图 contentType 不在 supported-types → IMAGEGEN_UNSUPPORTED_SOURCE_TYPE")
    void validate_unsupportedType_throws() {
        reader.byId.clear();
        reader.byId.put("img-1", new SourceImageInfo("img-1", PKG, "sku-1", "key/1", "image/gif", null, null));
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), "重绘", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, PKG))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_UNSUPPORTED_SOURCE_TYPE);
    }

    @Test
    @DisplayName("缺少 packageId → IMAGEGEN_SOURCE_NOT_IN_PACKAGE(归属前提缺失)")
    void validate_missingPackageId_throws() {
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), "重绘", Map.of(), null);
        assertThatThrownBy(() -> validator.validate(inputs, CONV, ""))
            .isInstanceOf(PixFlowException.class)
            .extracting(e -> ((PixFlowException) e).code())
            .isEqualTo(ImagegenErrorCode.IMAGEGEN_SOURCE_NOT_IN_PACKAGE);
    }

    @Test
    @DisplayName("ImagegenPlan record 不可变性:返回的 list 修改不影响 record 内部状态")
    void validate_returnedPlan_isImmutable() {
        reader.byId.put("img-1", info("img-1"));
        ImagegenPlanInputs inputs = inputs(List.of("img-1"), "重绘", Map.of("style", "A"), null);
        ImagegenPlan plan = validator.validate(inputs, CONV, PKG);
        // record 的 list 字段是 List.copyOf 后的不可变副本
        assertThatThrownBy(() -> plan.sourceImageIds().add("evil"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // —— helper ——

    private static ImagegenPlanInputs inputs(List<String> ids, String prompt, Map<String, Object> params, String note) {
        return new ImagegenPlanInputs(ids, prompt, note, params);
    }

    private static SourceImageInfo info(String imageId) {
        return new SourceImageInfo(imageId, PKG, "sku-" + imageId, "packages/" + PKG + "/" + imageId,
            "image/png", null, null);
    }

    /** 单测用 fake SourceImageReader,行为由 imageId 与映射表决定。 */
    static class FakeSourceImageReader implements SourceImageReader {
        final Map<String, SourceImageInfo> byId = new HashMap<>();
        private List<String> lastCalledIds = List.of();

        List<String> lastCalledIds() {
            return lastCalledIds;
        }

        @Override
        public List<SourceImageInfo> findAll(List<String> imageIds, String packageId) {
            this.lastCalledIds = List.copyOf(imageIds);
            Set<String> idSet = imageIds.stream().collect(Collectors.toSet());
            return byId.entrySet().stream()
                .filter(e -> idSet.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        }
    }
}

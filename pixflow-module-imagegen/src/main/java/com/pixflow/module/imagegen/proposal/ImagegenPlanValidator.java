package com.pixflow.module.imagegen.proposal;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 提案深校验(对齐 imagegen.md §5.1 / §十二)。
 *
 * <p>校验顺序:
 * <ol>
 *   <li>referenceKey 是 canonical concrete IMAGE key</li>
 *   <li>prompt 长度在 [min, max] 区间内</li>
 *   <li>params 仅含白名单键(白名单外的键直接拒绝)</li>
 *   <li>经 {@link SourceImageReader} 校验源图存在 + 归属 packageId + contentType 在白名单</li>
 * </ol>
 *
 * <p>校验失败 → {@link PixFlowException}({@link ImagegenErrorCode}, VALIDATION/SKIP 或 BUSINESS_RULE/SKIP)。
 * 该 validator 是无状态纯函数(除 SPI 调用外),便于单测。
 */
public class ImagegenPlanValidator {

    private final ImagegenProperties properties;

    private final SourceImageReader sourceImageReader;

    private final CanonicalAssetReferenceCodec referenceCodec =
            new CanonicalAssetReferenceCodec();

    public ImagegenPlanValidator(ImagegenProperties properties, SourceImageReader sourceImageReader) {
        this.properties = properties;
        this.sourceImageReader = sourceImageReader;
    }

    /**
     * 深校验入参；返回绑定一个 concrete IMAGE key 的规范化 {@link ImagegenPlan}。
     *
     * @throws PixFlowException 任一校验项失败
     */
    public ImagegenPlan validate(ImagegenPlanInputs inputs, String conversationId) {
        // 0. 浅层兜底:必填字段
        if (inputs == null) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID, "入参不能为空");
        }
        if (inputs.prompt() == null || inputs.prompt().isBlank()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID, "prompt 不能为空");
        }
        ImageAssetReferenceKey source = requireImageReference(inputs.referenceKey());

        // 2. prompt 长度
        String prompt = inputs.prompt().trim();
        int promptLen = prompt.length();
        int min = properties.getProposal().getPromptMinChars();
        int max = properties.getProposal().getPromptMaxChars();
        if (promptLen < min || promptLen > max) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID,
                "prompt 长度越界: " + promptLen + " 不在 [" + min + ", " + max + "]");
        }

        // 3. params 白名单归一；白名单外键直接拒绝，不能静默改变用户请求。
        Map<String, Object> normalizedParams = new TreeMap<>();
        Set<String> allowedKeys = new LinkedHashSet<>(properties.getProposal().getAllowedParamKeys());
        Map<String, Object> rawParams = inputs.params() == null ? Map.of() : inputs.params();
        Map<String, Object> droppedKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawParams.entrySet()) {
            String key = entry.getKey();
            if (!allowedKeys.contains(key)) {
                droppedKeys.put(key, entry.getValue());
                continue;
            }
            // 保留原值;后续 payloadHasher 会做最简化数值归一
            normalizedParams.put(key, entry.getValue());
        }
        // 白名单外有键 → 直接拒(防止 agent 偷偷塞机密键,如 model/secrets 等)
        if (!droppedKeys.isEmpty()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID,
                "params 含白名单外键: " + droppedKeys.keySet() + " (allowed=" + allowedKeys + ")");
        }

        // 4. 源图存在 / 归属 / 类型白名单(经 SPI)
        String imageId = Long.toString(source.imageId());
        String packageId = Long.toString(source.packageId());
        java.util.List<SourceImageInfo> infos = sourceImageReader.findAll(
                java.util.List.of(imageId), packageId);
        if (infos == null || infos.size() != 1 || !imageId.equals(infos.getFirst().imageId())) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND,
                "源图不存在");
        }

        // 校验归属 + contentType
        Set<String> supportedTypes = new LinkedHashSet<>(properties.getSource().getSupportedTypes());
        for (SourceImageInfo info : infos) {
            if (!packageId.equals(info.packageId())) {
                throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_SOURCE_NOT_IN_PACKAGE,
                    "源图不归属当前 packageId: imageId=" + info.imageId() + ", expected=" + packageId
                        + ", actual=" + info.packageId());
            }
            if (info.contentType() == null || !supportedTypes.contains(info.contentType())) {
                throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_UNSUPPORTED_SOURCE_TYPE,
                    "源图类型不在白名单: imageId=" + info.imageId() + ", contentType=" + info.contentType()
                        + " (supported=" + supportedTypes + ")");
            }
        }

        return new ImagegenPlan(
            referenceCodec.serialize(source),
            prompt,
            normalizedParams,
            inputs.note(), // note 原样保留,不参与 hash
            conversationId,
            source.packageId());
    }

    private ImageAssetReferenceKey requireImageReference(String referenceKey) {
        try {
            AssetReferenceKey parsed = referenceCodec.parse(referenceKey);
            if (parsed instanceof ImageAssetReferenceKey imageReference) {
                return imageReference;
            }
        } catch (IllegalArgumentException invalidReference) {
            throw new PixFlowException(
                    ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND,
                    "referenceKey 不是 canonical IMAGE key",
                    invalidReference);
        }
        throw new PixFlowException(
                ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND,
                "referenceKey 必须指向一个 concrete IMAGE");
    }
}

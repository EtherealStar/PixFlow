package com.pixflow.module.imagegen.proposal;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 提案深校验(对齐 imagegen.md §5.1 / §十二)。
 *
 * <p>校验顺序:
 * <ol>
 *   <li>源图集非空 + 去重 + 张数 ≤ max-source-images</li>
 *   <li>prompt 长度在 [min, max] 区间内</li>
 *   <li>params 仅含白名单键(白名单外的键被丢弃并记入 details)</li>
 *   <li>经 {@link SourceImageReader} 校验源图存在 + 归属 packageId + contentType 在白名单</li>
 * </ol>
 *
 * <p>校验失败 → {@link PixFlowException}({@link ImagegenErrorCode}, VALIDATION/SKIP 或 BUSINESS_RULE/SKIP)。
 * 该 validator 是无状态纯函数(除 SPI 调用外),便于单测。
 */
public class ImagegenPlanValidator {

    private final ImagegenProperties properties;
    private final SourceImageReader sourceImageReader;

    public ImagegenPlanValidator(ImagegenProperties properties, SourceImageReader sourceImageReader) {
        this.properties = properties;
        this.sourceImageReader = sourceImageReader;
    }

    /**
     * 深校验入参;返回规范化后的 {@link ImagegenPlan}(含字典序排序的 sourceImageIds、白名单归一后的 params)。
     *
     * @throws PixFlowException 任一校验项失败
     */
    public ImagegenPlan validate(ImagegenPlanInputs inputs, String conversationId, String packageId) {
        // 0. 浅层兜底:必填字段
        if (inputs == null) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID, "入参不能为空");
        }
        if (inputs.source_image_ids() == null || inputs.source_image_ids().isEmpty()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND, "source_image_ids 不能为空");
        }
        if (inputs.prompt() == null || inputs.prompt().isBlank()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID, "prompt 不能为空");
        }
        if (packageId == null || packageId.isBlank()) {
            // 无 packageId 无法校验归属,直接拒
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_SOURCE_NOT_IN_PACKAGE, "缺少 packageId,无法校验源图归属");
        }

        // 1. 源图集去重
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (String id : inputs.source_image_ids()) {
            if (id == null || id.isBlank()) {
                throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND, "source_image_ids 含空值");
            }
            uniqueIds.add(id);
        }
        if (uniqueIds.size() > properties.getProposal().getMaxSourceImages()) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_TOO_MANY_SOURCES,
                "源图张数超过上限: " + uniqueIds.size() + " > " + properties.getProposal().getMaxSourceImages());
        }

        // 2. prompt 长度
        String prompt = inputs.prompt().trim();
        int promptLen = prompt.length();
        int min = properties.getProposal().getPromptMinChars();
        int max = properties.getProposal().getPromptMaxChars();
        if (promptLen < min || promptLen > max) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_PROMPT_INVALID,
                "prompt 长度越界: " + promptLen + " 不在 [" + min + ", " + max + "]");
        }

        // 3. params 白名单归一(白名单外的键丢弃,避免污染 payloadHash)
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
        List<SourceImageInfo> infos = sourceImageReader.findAll(List.copyOf(uniqueIds), packageId);
        if (infos == null || infos.size() != uniqueIds.size()) {
            Set<String> foundIds = new LinkedHashSet<>();
            if (infos != null) {
                for (SourceImageInfo info : infos) {
                    foundIds.add(info.imageId());
                }
            }
            Set<String> missing = new LinkedHashSet<>(uniqueIds);
            missing.removeAll(foundIds);
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_SOURCE_IMAGE_NOT_FOUND,
                "源图不存在: " + missing);
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

        // 5. 规范化 sourceImageIds(按字典序)入 record
        List<String> sortedIds = uniqueIds.stream().sorted().toList();

        return new ImagegenPlan(
            sortedIds,
            prompt,
            normalizedParams,
            inputs.note(), // note 原样保留,不参与 hash
            conversationId,
            packageId);
    }
}
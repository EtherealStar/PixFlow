package com.pixflow.module.imagegen.exec;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.imagegen.ImageGenClient;
import com.pixflow.infra.ai.imagegen.ImageGenRequest;
import com.pixflow.infra.ai.imagegen.ImageGenResult;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageException;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.error.ImagegenErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单图重绘默认实现(对齐 imagegen.md §8.2 / §十 / §十二)。
 *
 * <p>redraw 流程:
 * <ol>
 *   <li>stat 源图:size 超过 {@code source.max-read-bytes} → 抛 {@code IMAGEGEN_OUTPUT_BYTES_TOO_LARGE}
 *       (源图字节防护,保护堆;与 dag 的 source-bytes-limit 对称)</li>
 *   <li>{@code objectStorage.getStream} 解析源图字节</li>
 *   <li>{@code imageGenClient.generate} 重绘</li>
 *   <li>字节预检:result.image.length 超过 {@code output.max-output-bytes} → 抛 {@code IMAGEGEN_OUTPUT_BYTES_TOO_LARGE}
 *       (生成图字节防护;不调 put)</li>
 *   <li>{@code objectStorage.put} 落 {@code GENERATED} 桶,key 用 {@code StorageKeys.generated}</li>
 *   <li>返回 {@link GeneratedArtifact}</li>
 * </ol>
 *
 * <p>约束:
 * <ul>
 *   <li>ai 抛 {@code PixFlowException} 原样上抛(不吞、不重试;重试在 ai 层 ModelRetryRunner)</li>
 *   <li>{@code StorageException} 转 {@code IMAGEGEN_STORAGE_WRITE_FAILED}(写失败细化)</li>
 *   <li>{@code MODEL_PROVIDER_ERROR} 转 {@code IMAGEGEN_CONTENT_POLICY_VIOLATION}(内容审查细化)</li>
 * </ul>
 *
 * <p>无状态:不写 process_result、不发进度、不持取消、不持任何缓存。
 */
public class DefaultImageGenExecutor implements ImageGenExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultImageGenExecutor.class);

    private final ImageGenClient imageGenClient;
    private final ObjectStorage objectStorage;
    private final ImagegenProperties properties;

    public DefaultImageGenExecutor(ImageGenClient imageGenClient,
                                   ObjectStorage objectStorage,
                                   ImagegenProperties properties) {
        this.imageGenClient = imageGenClient;
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    @Override
    public GeneratedArtifact redraw(GenerativeUnitSpec spec) {
        // 1. stat 源图:源图字节防护
        StoredObjectMetadata meta;
        try {
            meta = objectStorage.stat(spec.sourceLocation());
        } catch (StorageException e) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED,
                "源图元数据读取失败: " + spec.sourceImageId(), e);
        }
        long maxRead = properties.getSource().getMaxReadBytes();
        if (meta.size() > maxRead) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,
                "源图字节超过防护阈值: " + meta.size() + " > " + maxRead
                    + " (imageId=" + spec.sourceImageId() + ")");
        }

        // 2. 解析源图字节
        byte[] sourceBytes = readSourceBytes(spec.sourceLocation());

        // 3. ai 重绘
        ImageGenResult result;
        try {
            result = imageGenClient.generate(new ImageGenRequest(
                sourceBytes,
                meta.contentType() == null ? "image/png" : meta.contentType(),
                spec.prompt(),
                toChatOptions(spec.params())));
        } catch (PixFlowException pe) {
            // 原样上抛(不吞、不重试);若是 ai 的 MODEL_PROVIDER_ERROR 细化为生图内容审查
            if (pe.code() == AiErrorCode.MODEL_PROVIDER_ERROR) {
                throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_CONTENT_POLICY_VIOLATION,
                    "生图被供应商拒绝: " + safe(pe.getMessage()), pe);
            }
            throw pe;
        }

        // 4. 生成图字节预检(生产级必做,与 dag source-bytes-limit 对称)
        long maxOut = properties.getOutput().getMaxOutputBytes();
        if (result.image().length > maxOut) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_OUTPUT_BYTES_TOO_LARGE,
                "生成图字节超过防护阈值: " + result.image().length + " > " + maxOut
                    + " (imageId=" + spec.sourceImageId() + ")");
        }

        // 5. 落 GENERATED 桶
        ObjectLocation outputLoc = StorageKeys.generated(
            hashTaskId(spec.taskId()),
            spec.skuId(),
            // StorageKeys.generated 接受 long imageId;spec.sourceImageId 是 String,
            // 数字串直接 parseLong;非数字走 SHA-256 摘要前 8 字节
            // (同一 imageId 多次跑 key 一致,支持 redraw 幂等)。
            hashImageId(spec.sourceImageId()),
            spec.outputExt());
        ObjectRef ref;
        try (InputStream in = new ByteArrayInputStream(result.image())) {
            ref = objectStorage.put(outputLoc, in, result.image().length, result.contentType());
        } catch (StorageException e) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED,
                "生成图落桶失败: " + outputLoc, e);
        } catch (IOException e) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED,
                "生成图流处理失败: " + outputLoc, e);
        }

        log.debug("redraw ok: taskId={}, skuId={}, imageId={}, outputKey={}, bytes={}",
            spec.taskId(), spec.skuId(), spec.sourceImageId(), ref.key(), ref.size());
        return new GeneratedArtifact(ref, result.contentType(), result.usage());
    }

    /** 读取源图字节;遇 StorageException 转 IMAGEGEN_STORAGE_WRITE_FAILED(读取失败同样归 storage 类)。 */
    private byte[] readSourceBytes(ObjectLocation sourceLocation) {
        try (InputStream in = objectStorage.getStream(sourceLocation)) {
            return in.readAllBytes();
        } catch (StorageException e) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED,
                "源图读取失败: " + sourceLocation, e);
        } catch (IOException e) {
            throw new PixFlowException(ImagegenErrorCode.IMAGEGEN_STORAGE_WRITE_FAILED,
                "源图 IO 失败: " + sourceLocation, e);
        }
    }

    /** 把 params Map 转换为 ChatOptions(temperature=null 让 ai 走默认)。 */
    private static ChatOptions toChatOptions(java.util.Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new ChatOptions(null, null, Duration.ofSeconds(60));
        }
        Double temp = null;
        Object t = params.get("temperature");
        if (t instanceof Number n) {
            temp = n.doubleValue();
        } else if (t instanceof String s) {
            try { temp = Double.parseDouble(s); } catch (NumberFormatException ignored) { /* keep null */ }
        }
        Integer seed = null;
        Object seedObj = params.get("seed");
        if (seedObj instanceof Number n) {
            seed = n.intValue();
        }
        return new ChatOptions(temp, seed, Duration.ofSeconds(60));
    }

    /**
     * 把 String imageId 折算为 long(用于 {@link StorageKeys#generated})。
     *
     * <p>策略:优先 parseLong(若 imageId 本身就是数字串);否则取 SHA-256 摘要前 8 字节的 long 值。
     * 该转换仅用于落桶 key 命名,不影响 ImagegenPlan 自身 imageId 字段的事实。
     */
    private static long hashImageId(String imageId) {
        return stableHashToLong(imageId);
    }

    /** 把 String taskId 折算为 long(用于 {@link StorageKeys#generated})。 */
    private static long hashTaskId(String taskId) {
        return stableHashToLong(taskId);
    }

    private static long stableHashToLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                long v = 0L;
                for (int i = 0; i < 8; i++) {
                    v = (v << 8) | (digest[i] & 0xFFL);
                }
                return v;
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
        }
    }

    /** 默认输出扩展名(供单元 / 集成测试或上层组装 spec 时引用)。 */
    public String defaultOutputExt() {
        return properties.getOutput().getDefaultExt();
    }

    /** 默认 task 落桶桶类型(暴露给上层确认是 GENERATED,避免硬编码)。 */
    public static BucketType generatedBucket() {
        return BucketType.GENERATED;
    }

    private static String safe(String message) {
        if (message == null) return "";
        // 简单截断防爆日志
        return message.length() > 256 ? message.substring(0, 256) + "..." : message;
    }

    /** 占位 TokenUsage,当 ai 不回 usage 时兜底(理论上 ImageGenResult.usage 必填,此处仅防御)。 */
    public static TokenUsage zeroUsage() {
        return new TokenUsage(0L, 0L, 0L);
    }
}
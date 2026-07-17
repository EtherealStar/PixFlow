package com.pixflow.module.imagegen.confirm;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * payloadHash 计算器(对齐 imagegen.md §七 / §十六.11)。
 *
 * <p>规范化口径:
 * <ol>
 *   <li>{@code sourceReferenceKey} 是 canonical IMAGE key</li>
 *   <li>{@code prompt} trim</li>
 *   <li>{@code params} 仅含白名单键,按字典序归一;白名单外键 / 缺失字段不补默认值,不参与 hash</li>
 *   <li>{@code note} / {@code conversationId} / {@code packageId} 不参与 hash(用户输入 + 会话元数据不应影响 fingerprint)</li>
 * </ol>
 *
 * <p>用 Jackson 序列化(字段按字典序),再用 SHA-256 算字节摘要 → 16 进制字符串。
 *
 * <p>与 dag.md §7.4 的 {@code CanonicalJson} 思路一致:
 * hash 是 fingerprint,不携带"用户拖拽顺序"这类业务意图;用户的业务意图由 prompt + params 承载。
 */
@Component
public class ImagegenPayloadHasher {

    /**
     * params 白名单(与 imagegen.md §十三保持一致)。
     * 该白名单定义 payloadHash 哪些键参与计算;白名单外的键即便出现在 plan 里也不参与。
     * 注意:校验阶段的"白名单外键拒收"在 {@code ImagegenPlanValidator} 完成,
     * 此处是"白名单内键归一化",两者是同一组键但职责不同。
     */
    private static final Set<String> DEFAULT_HASHABLE_KEYS = Set.of("style", "strength", "negative_prompt", "seed");

    /** 复用的规范化 ObjectMapper:字段按字典序排序,日期不写成时间戳。 */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /** 可参与 hash 的 params 键集合(默认 4 个,如有扩展可由 properties 注入)。 */
    private final Set<String> hashableKeys;

    public ImagegenPayloadHasher() {
        this(DEFAULT_HASHABLE_KEYS);
    }

    @Autowired
    public ImagegenPayloadHasher(com.pixflow.module.imagegen.config.ImagegenProperties properties) {
        this(properties == null
            ? DEFAULT_HASHABLE_KEYS
            : new TreeSet<>(properties.getProposal().getAllowedParamKeys()));
    }

    /** 显式构造(便于单测与 SPI 注入)。 */
    public ImagegenPayloadHasher(Set<String> hashableKeys) {
        this.hashableKeys = hashableKeys == null || hashableKeys.isEmpty()
            ? DEFAULT_HASHABLE_KEYS
            : Set.copyOf(hashableKeys);
    }

    /**
     * 对规范化 plan 算 payloadHash(SHA-256 hex)。
     *
     * @throws IllegalStateException SHA-256 不可用或序列化失败
     */
    public String hash(ImagegenPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        // params 白名单内键按字典序归一，白名单外或 null 值不进入 hash 树。
        Map<String, Object> canonicalParams = new TreeMap<>();
        if (plan.params() != null) {
            for (String key : hashableKeys) {
                if (plan.params().containsKey(key)) {
                    Object value = plan.params().get(key);
                    // null 值视为「未填」,不出现在 hash 中(与缺失等价)
                    if (value != null) {
                        canonicalParams.put(key, value);
                    }
                }
            }
        }

        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("sourceReferenceKey", plan.sourceReferenceKey());
        canonical.put("prompt", plan.prompt() == null ? "" : plan.prompt().trim());
        canonical.put("params", canonicalParams);

        try {
            byte[] bytes = CANONICAL.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("payloadHash 序列化失败", e);
        }
    }

    /** 标准 UTF-8 编码(供外部对齐使用)。 */
    public static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}

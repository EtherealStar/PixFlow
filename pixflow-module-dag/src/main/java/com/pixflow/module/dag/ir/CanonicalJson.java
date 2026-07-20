package com.pixflow.module.dag.ir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON 规范化工具。
 *
 * <p>规则集(对齐 dag.md §7.4):
 * <ol>
 *   <li>对象键按字典序排序(递归)</li>
 *   <li>数组顺序保留(节点 edges、compose_group 的 order 是有序的)</li>
 *   <li>数字统一为最简形式(BigDecimal 去除尾随零);整数无小数点</li>
 *   <li>字符串原样保留(不做大小写归一化、不规范化业务标识符)</li>
 *   <li>布尔/null 保留字面量</li>
 *   <li>缺失字段 vs null:序列化时把缺失补 null(本工具接受已是 Map 的入参,缺失不会被补,因为输入已结构化)</li>
 *   <li>不参与业务内容归一化(MinIO key、URL 等原样保留)</li>
 * </ol>
 *
 * <p>branchId 与 payload_hash 共用本工具,确保恢复语义与确认一致性口径一致。
 */
public final class CanonicalJson {

    /** 复用的规范化 ObjectMapper:SORT_PROPERTIES_ALPHABETICALLY + WRITE_DATES_AS_TIMESTAMPS=false。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(false));

    private CanonicalJson() {
    }

    /** 把任意 Java 对象(Map/List/String/Number/Boolean)规范化为 UTF-8 字节序列。 */
    public static byte[] canonicalize(Object value) {
        try {
            JsonNode node = MAPPER.valueToTree(normalize(value));
            return MAPPER.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical JSON 序列化失败", e);
        }
    }

    /** 与 {@link #canonicalize(Object)} 等价,返回 String。 */
    public static String canonicalizeToString(Object value) {
        return new String(canonicalize(value), StandardCharsets.UTF_8);
    }

    /** 递归规范化:把 Map 视为对象、List 视为数组、其他原样。 */
    @SuppressWarnings("unchecked")
    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            // 用 TreeMap 保证字典序排序
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> arr = new ArrayList<>(list.size());
            for (Object item : list) {
                arr.add(normalize(item));
            }
            return arr;
        }
        if (value instanceof Number n) {
            return normalizeNumber(n);
        }
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        // 其他类型(enum、record 等)交给 Jackson 走默认路径
        return value;
    }

    /** 数字规范化:BigDecimal 去除尾随零;整数无小数点;Double/Long 走 BigDecimal 统一处理。 */
    private static Object normalizeNumber(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return n;
        }
        BigDecimal bd;
        if (n instanceof BigDecimal b) {
            bd = b.stripTrailingZeros();
        } else if (n instanceof Double || n instanceof Float) {
            bd = new BigDecimal(String.valueOf(n)).stripTrailingZeros();
        } else {
            bd = new BigDecimal(n.toString()).stripTrailingZeros();
        }
        // 形如 "1E+1" → "10";stripTrailingZeros 后保留 scale,但 Jackson 会按数值输出
        if (bd.scale() <= 0) {
            return bd.toBigInteger();
        }
        return bd;
    }

    /** 把任意 JSON 字符串解析后再次规范化为字节(便于把不同来源的 JSON 折算到统一形态)。 */
    public static byte[] recanonicalize(byte[] jsonBytes) {
        try {
            JsonNode node = MAPPER.readTree(jsonBytes);
            return MAPPER.writeValueAsBytes(canonicalizeNode(node));
        } catch (Exception e) {
            throw new IllegalArgumentException("无效 JSON 输入", e);
        }
    }

    private static JsonNode canonicalizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            ObjectNode sorted = MAPPER.createObjectNode();
            // 用 TreeMap 按 key 字典序排序
            Map<String, JsonNode> ordered = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = src.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                ordered.put(e.getKey(), canonicalizeNode(e.getValue()));
            }
            for (Map.Entry<String, JsonNode> e : ordered.entrySet()) {
                sorted.set(e.getKey(), e.getValue());
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode src = (ArrayNode) node;
            ArrayNode arr = MAPPER.createArrayNode();
            for (int i = 0; i < src.size(); i++) {
                arr.add(canonicalizeNode(src.get(i)));
            }
            return arr;
        }
        if (node.isNumber()) {
            BigDecimal bd;
            if (node.isBigDecimal()) {
                bd = node.decimalValue().stripTrailingZeros();
            } else {
                bd = new BigDecimal(node.asText()).stripTrailingZeros();
            }
            if (bd.scale() <= 0) {
                return MAPPER.getNodeFactory().numberNode(bd.toBigInteger());
            }
            return MAPPER.getNodeFactory().numberNode(bd);
        }
        // string/bool 原样
        return node;
    }

    /** 返回只读空 Map(供 record 字段无值时使用)。 */
    public static <K, V> Map<K, V> emptyMap() {
        return Collections.emptyMap();
    }
}
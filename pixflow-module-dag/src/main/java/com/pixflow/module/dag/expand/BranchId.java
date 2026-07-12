package com.pixflow.module.dag.expand;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 确定性 branchId 派生器。
 *
 * <p>派生规则(对齐 dag.md §7.4):
 * <pre>
 *   pathHash = sha256( canonicalJson("sourceId -> ... -> sinkId") )
 *   paramHash = sha256( canonicalJson(每节点按路径顺序的 params 拼接) )
 *   branchId = sha256( pathHash + "|" + paramHash )
 * </pre>
 *
 * <p>同一类型化计划的同一条 source→sink 路径,每次调用得到相同 branchId。
 * 这保证崩溃恢复时 {@code UnitKey.branchId} 对得上 process_result.branch_id。
 */
public final class BranchId {

    private BranchId() {}

    /** 给定 source/sink id 序列与参数 Map 列表,派生确定性 branchId。 */
    public static String derive(java.util.List<String> pathNodeIds,
                                java.util.List<java.util.Map<String, Object>> orderedParams) {
        Objects.requireNonNull(pathNodeIds, "pathNodeIds");
        Objects.requireNonNull(orderedParams, "orderedParams");
        if (pathNodeIds.isEmpty()) {
            throw new IllegalArgumentException("pathNodeIds 不能为空");
        }
        // 1. 路径字符串:"n1->n3->n7"
        String pathStr = String.join("->", pathNodeIds);
        byte[] pathHash = sha256(com.pixflow.module.dag.ir.CanonicalJson.canonicalize(pathStr));
        // 2. 参数列表:按顺序拼成 List<Map>
        byte[] paramHash = sha256(com.pixflow.module.dag.ir.CanonicalJson.canonicalize(orderedParams));
        // 3. 拼接
        String concat = HexFormat.of().formatHex(pathHash) + "|" + HexFormat.of().formatHex(paramHash);
        byte[] finalHash = sha256(concat.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(finalHash);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

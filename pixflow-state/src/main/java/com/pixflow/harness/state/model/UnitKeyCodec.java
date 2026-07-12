package com.pixflow.harness.state.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Work Unit 持久化身份与对象路径摘要的唯一编码入口。 */
public final class UnitKeyCodec {
    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private UnitKeyCodec() {
    }

    public static String encode(UnitKey unit) {
        Objects.requireNonNull(unit, "unit");
        // taskId 已由 process_result.task_id 隔离，不重复写入 unit_key，便于派生任务复用选择快照。
        return String.join("|", VERSION, unit.kind().name(), encodePart(unit.memberId()), encodePart(unit.branchId()));
    }

    public static UnitKey decode(String taskId, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("encoded unit key must not be blank");
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("unsupported unit key format");
        }
        try {
            return new UnitKey(taskId, UnitKind.valueOf(parts[1]), decodePart(parts[2]), decodePart(parts[3]));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid encoded unit key", ex);
        }
    }

    public static String sha256(UnitKey unit) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encode(unit).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String encodePart(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}

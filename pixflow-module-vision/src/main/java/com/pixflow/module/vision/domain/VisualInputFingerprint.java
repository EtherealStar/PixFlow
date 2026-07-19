package com.pixflow.module.vision.domain;

import com.pixflow.module.vision.api.VisualAsset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class VisualInputFingerprint {
    private VisualInputFingerprint() {
    }

    public static String forSku(List<VisualAsset> assets) {
        String joined = assets.stream()
                .map(VisualAsset::contentHash)
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return sha256(joined);
    }

    public static String forImage(VisualAsset asset) {
        return asset.contentHash();
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}

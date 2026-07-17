package com.pixflow.contracts.asset;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 无状态、线程安全的 canonical Asset Reference codec。 */
public final class CanonicalAssetReferenceCodec implements AssetReferenceCodec {

    private static final String PACKAGE_PREFIX = "package:";

    private static final String SKU_SEGMENT = "/sku:";

    private static final String IMAGE_SEGMENT = "/image:";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    @Override
    public AssetReferenceKey parse(String referenceKey) {
        if (referenceKey == null || referenceKey.isBlank()) {
            throw invalid(InvalidAssetReferenceReason.NULL_OR_BLANK);
        }
        if (!referenceKey.startsWith(PACKAGE_PREFIX)) {
            throw invalid(InvalidAssetReferenceReason.INVALID_SHAPE);
        }

        AssetReferenceKey parsed = parseKnownShape(referenceKey);
        // 重序列化比较把大小写、leading zero 和多余转义统一收敛为唯一文本。
        if (!serialize(parsed).equals(referenceKey)) {
            throw invalid(InvalidAssetReferenceReason.NON_CANONICAL);
        }
        return parsed;
    }

    @Override
    public String serialize(AssetReferenceKey reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference instanceof PackageAssetReferenceKey packageReference) {
            return PACKAGE_PREFIX + packageReference.packageId();
        }
        if (reference instanceof SkuAssetReferenceKey skuReference) {
            return PACKAGE_PREFIX + skuReference.packageId()
                    + SKU_SEGMENT + encodeSku(skuReference.skuId());
        }
        if (reference instanceof ImageAssetReferenceKey imageReference) {
            return PACKAGE_PREFIX + imageReference.packageId()
                    + IMAGE_SEGMENT + imageReference.imageId();
        }
        throw new IllegalArgumentException("unsupported asset reference type");
    }

    private static AssetReferenceKey parseKnownShape(String referenceKey) {
        int segmentStart = referenceKey.indexOf('/');
        if (segmentStart < 0) {
            return new PackageAssetReferenceKey(parsePositiveId(
                    referenceKey.substring(PACKAGE_PREFIX.length())));
        }

        long packageId = parsePositiveId(referenceKey.substring(
                PACKAGE_PREFIX.length(), segmentStart));
        String tail = referenceKey.substring(segmentStart);
        if (tail.startsWith(SKU_SEGMENT)) {
            String encodedSku = tail.substring(SKU_SEGMENT.length());
            return new SkuAssetReferenceKey(packageId, decodeSku(encodedSku));
        }
        if (tail.startsWith(IMAGE_SEGMENT)) {
            String imageId = tail.substring(IMAGE_SEGMENT.length());
            return new ImageAssetReferenceKey(packageId, parsePositiveId(imageId));
        }
        throw invalid(InvalidAssetReferenceReason.INVALID_SHAPE);
    }

    private static long parsePositiveId(String text) {
        if (text.isEmpty() || text.length() > 1 && text.charAt(0) == '0') {
            throw invalid(InvalidAssetReferenceReason.INVALID_IDENTIFIER);
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current < '0' || current > '9') {
                throw invalid(InvalidAssetReferenceReason.INVALID_IDENTIFIER);
            }
        }
        try {
            long value = Long.parseLong(text);
            if (value <= 0) {
                throw invalid(InvalidAssetReferenceReason.INVALID_IDENTIFIER);
            }
            return value;
        } catch (NumberFormatException overflow) {
            throw invalid(InvalidAssetReferenceReason.INVALID_IDENTIFIER);
        }
    }

    private static String encodeSku(String skuId) {
        byte[] bytes = skuId.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int unsigned = current & 0xFF;
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%')
                        .append(HEX[unsigned >>> 4])
                        .append(HEX[unsigned & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static String decodeSku(String encoded) {
        if (encoded.isEmpty()) {
            throw invalid(InvalidAssetReferenceReason.INVALID_SKU);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int index = 0; index < encoded.length();) {
            char current = encoded.charAt(index);
            if (current == '%') {
                if (index + 2 >= encoded.length()) {
                    throw invalid(InvalidAssetReferenceReason.INVALID_SKU);
                }
                int high = hexValue(encoded.charAt(index + 1));
                int low = hexValue(encoded.charAt(index + 2));
                if (high < 0 || low < 0) {
                    throw invalid(InvalidAssetReferenceReason.INVALID_SKU);
                }
                bytes.write(high << 4 | low);
                index += 3;
            } else {
                // canonical 文本只允许未转义的 ASCII unreserved 字节。
                if (current > 0x7F || !isUnreserved(current)) {
                    throw invalid(InvalidAssetReferenceReason.INVALID_SKU);
                }
                bytes.write(current);
                index++;
            }
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
            AssetReferenceValidation.requireSkuId(decoded);
            return decoded;
        } catch (CharacterCodingException | IllegalArgumentException invalidSku) {
            throw invalid(InvalidAssetReferenceReason.INVALID_SKU);
        }
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static int hexValue(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }

    private static InvalidAssetReferenceException invalid(InvalidAssetReferenceReason reason) {
        return new InvalidAssetReferenceException(reason);
    }
}

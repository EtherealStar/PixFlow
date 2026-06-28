package com.pixflow.module.file.ingest;

import com.pixflow.module.file.config.FileProperties;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class ImageAdmission {
    private final Set<String> allowedExtensions;
    private final boolean magicBytesCheck;
    private final long maxImageBytes;

    public ImageAdmission(FileProperties properties) {
        FileProperties.Image image = properties.getImage();
        this.allowedExtensions = image.getAllowedExtensions().stream()
                .map(ext -> ext.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.magicBytesCheck = image.isMagicBytesCheck();
        this.maxImageBytes = image.getMaxImageSize().toBytes();
    }

    public AdmissionResult inspect(String path, byte[] header, long size) {
        String extension = extension(path);
        if (!allowedExtensions.contains(extension)) {
            return AdmissionResult.rejected("UNSUPPORTED_IMAGE_FORMAT", "extension is not allowed: " + extension);
        }
        if (size > maxImageBytes) {
            return AdmissionResult.rejected("UPLOAD_TOO_LARGE", "image exceeds max size");
        }
        if (magicBytesCheck && !matchesMagic(extension, header)) {
            return AdmissionResult.rejected("UNSUPPORTED_IMAGE_FORMAT", "magic bytes do not match extension");
        }
        return AdmissionResult.accepted(contentType(extension));
    }

    private static boolean matchesMagic(String extension, byte[] header) {
        if (header == null || header.length < 4) {
            return false;
        }
        return switch (extension) {
            case "jpg", "jpeg" -> unsigned(header[0]) == 0xFF && unsigned(header[1]) == 0xD8 && unsigned(header[2]) == 0xFF;
            case "png" -> header.length >= 8
                    && unsigned(header[0]) == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                    && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A;
            case "webp" -> header.length >= 12
                    && ascii(header, 0, "RIFF") && ascii(header, 8, "WEBP");
            case "bmp" -> header[0] == 0x42 && header[1] == 0x4D;
            case "gif" -> ascii(header, 0, "GIF87a") || ascii(header, 0, "GIF89a");
            case "tif", "tiff" -> (header[0] == 0x49 && header[1] == 0x49 && header[2] == 0x2A && header[3] == 0x00)
                    || (header[0] == 0x4D && header[1] == 0x4D && header[2] == 0x00 && header[3] == 0x2A);
            default -> false;
        };
    }

    private static boolean ascii(byte[] header, int offset, String expected) {
        if (header.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (header[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static String extension(String path) {
        int index = path == null ? -1 : path.lastIndexOf('.');
        if (index < 0 || index == path.length() - 1) {
            return "";
        }
        return path.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "gif" -> "image/gif";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    public record AdmissionResult(boolean accepted, String contentType, String code, String message) {
        static AdmissionResult accepted(String contentType) {
            return new AdmissionResult(true, contentType, null, null);
        }

        static AdmissionResult rejected(String code, String message) {
            return new AdmissionResult(false, null, code, message);
        }
    }
}

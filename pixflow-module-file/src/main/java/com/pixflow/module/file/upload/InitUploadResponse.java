package com.pixflow.module.file.upload;

import java.util.List;

public record InitUploadResponse(
        String mode,
        String uploadId,
        Long packageId,
        String status,
        long chunkSize,
        int expectedChunks,
        List<Integer> uploadedChunks) {
    public static InitUploadResponse upload(String uploadId, long chunkSize, int expectedChunks, List<Integer> uploadedChunks) {
        return new InitUploadResponse("UPLOAD", uploadId, null, null, chunkSize, expectedChunks, uploadedChunks);
    }

    public static InitUploadResponse dedupReady(long packageId, String status) {
        return new InitUploadResponse("DEDUP", null, packageId, status, 0, 0, List.of());
    }

    public static InitUploadResponse dedupUploading(String uploadId, List<Integer> uploadedChunks) {
        return new InitUploadResponse("DEDUP", uploadId, null, "UPLOADING", 0, 0, uploadedChunks);
    }
}

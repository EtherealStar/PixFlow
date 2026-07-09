package com.pixflow.module.file.upload;

import java.util.List;

public record UploadSessionState(
        String uploadId,
        String fileHash,
        long size,
        long chunkSize,
        int expectedChunks,
        List<Integer> uploadedChunks,
        List<Integer> failedChunks,
        String status,
        Long packageId) {
}

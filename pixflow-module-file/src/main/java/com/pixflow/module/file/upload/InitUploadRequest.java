package com.pixflow.module.file.upload;

public record InitUploadRequest(String filename, long size, String fileHash, long chunkSize) {
}

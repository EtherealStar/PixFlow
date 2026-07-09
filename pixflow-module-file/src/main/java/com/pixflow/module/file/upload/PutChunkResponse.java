package com.pixflow.module.file.upload;

import java.util.List;

public record PutChunkResponse(String uploadId, int index, String status, List<Integer> uploadedChunks) {
}

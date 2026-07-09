package com.pixflow.module.file.upload;

record ChunkMetadata(int index, String chunkHash, long chunkSize, String minioKey) {
}

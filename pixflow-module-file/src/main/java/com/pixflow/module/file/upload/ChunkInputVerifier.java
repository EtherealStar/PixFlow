package com.pixflow.module.file.upload;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 流式读取分片时同步做字节上限和 SHA-256 校验，避免把上传体完整读入堆内存。
 */
final class ChunkInputVerifier extends FilterInputStream {
    private final MessageDigest digest;
    private final long expectedSize;
    private final String expectedHash;
    private long bytesRead;
    private boolean completed;

    ChunkInputVerifier(InputStream in, long expectedSize, String expectedHash) {
        super(in);
        this.expectedSize = expectedSize;
        this.expectedHash = expectedHash;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            onBytes(new byte[] {(byte) b}, 0, 1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            onBytes(b, off, n);
        }
        return n;
    }

    private void onBytes(byte[] b, int off, int len) throws IOException {
        bytesRead += len;
        if (bytesRead > expectedSize) {
            throw new IOException("chunk size exceeds declared length");
        }
        digest.update(b, off, len);
    }

    void verifyCompleted() {
        completed = true;
        if (bytesRead != expectedSize) {
            throw new PixFlowException(FileErrorCode.CHUNK_SIZE_MISMATCH, "chunk size mismatch");
        }
        String actualHash = HexFormat.of().formatHex(digest.digest());
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new PixFlowException(FileErrorCode.CHUNK_HASH_MISMATCH, "chunk hash mismatch");
        }
    }

    long bytesRead() {
        return bytesRead;
    }

    boolean completed() {
        return completed;
    }
}

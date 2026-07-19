package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import java.io.ByteArrayOutputStream;

final class BoundedArchiveOutput extends ByteArrayOutputStream {
    private final long limit;

    BoundedArchiveOutput(long limit) {
        this.limit = limit;
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        enforce(length);
        super.write(bytes, offset, length);
    }

    @Override
    public synchronized void write(int value) {
        enforce(1);
        super.write(value);
    }

    private void enforce(int added) {
        if ((long) count + added > limit) {
            throw new PixFlowException(FileErrorCode.ZIP_BOMB_DETECTED,
                    "archive entry actual size exceeds limit");
        }
    }
}

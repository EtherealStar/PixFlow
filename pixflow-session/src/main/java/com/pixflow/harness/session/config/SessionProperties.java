package com.pixflow.harness.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "pixflow.session")
public class SessionProperties {
    private WriteMode writeMode = WriteMode.BUFFERED;

    private Buffer buffer = new Buffer();

    private Load load = new Load();

    private Externalize externalize = new Externalize();

    private Seq seq = new Seq();

    public enum WriteMode {
        BUFFERED,
        SYNC
    }

    public WriteMode getWriteMode() {
        return writeMode;
    }

    public void setWriteMode(WriteMode writeMode) {
        this.writeMode = writeMode;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    public Load getLoad() {
        return load;
    }

    public void setLoad(Load load) {
        this.load = load;
    }

    public Externalize getExternalize() {
        return externalize;
    }

    public void setExternalize(Externalize externalize) {
        this.externalize = externalize;
    }

    public Seq getSeq() {
        return seq;
    }

    public void setSeq(Seq seq) {
        this.seq = seq;
    }

    public static class Buffer {
        private int flushMaxMessages = 50;

        private DataSize flushMaxBytes = DataSize.ofMegabytes(1);

        public int getFlushMaxMessages() {
            return flushMaxMessages;
        }

        public void setFlushMaxMessages(int flushMaxMessages) {
            this.flushMaxMessages = flushMaxMessages;
        }

        public DataSize getFlushMaxBytes() {
            return flushMaxBytes;
        }

        public void setFlushMaxBytes(DataSize flushMaxBytes) {
            this.flushMaxBytes = flushMaxBytes;
        }
    }

    public static class Load {
        private int maxMessages = 400;

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }

    public static class Externalize {
        private DataSize toolResultThreshold = DataSize.ofKilobytes(50);

        private int previewChars = 2048;

        public DataSize getToolResultThreshold() {
            return toolResultThreshold;
        }

        public void setToolResultThreshold(DataSize toolResultThreshold) {
            this.toolResultThreshold = toolResultThreshold;
        }

        public int getPreviewChars() {
            return previewChars;
        }

        public void setPreviewChars(int previewChars) {
            this.previewChars = previewChars;
        }
    }

    public static class Seq {
        private int allocationRetry = 3;

        public int getAllocationRetry() {
            return allocationRetry;
        }

        public void setAllocationRetry(int allocationRetry) {
            this.allocationRetry = allocationRetry;
        }
    }
}

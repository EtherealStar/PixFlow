package com.pixflow.module.task.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.task")
public class TaskProperties {
    private final Create create = new Create();
    private final Worker worker = new Worker();
    private final Lock lock = new Lock();
    private final Recovery recovery = new Recovery();
    private final Cancel cancel = new Cancel();
    private final Download download = new Download();
    private final Terminal terminal = new Terminal();
    private final Progress progress = new Progress();
    private final Mq mq = new Mq();

    public Create getCreate() { return create; }
    public Worker getWorker() { return worker; }
    public Lock getLock() { return lock; }
    public Recovery getRecovery() { return recovery; }
    public Cancel getCancel() { return cancel; }
    public Download getDownload() { return download; }
    public Terminal getTerminal() { return terminal; }
    public Progress getProgress() { return progress; }
    public Mq getMq() { return mq; }

    public static class Create {
        private Duration idempotencyTtl = Duration.ofHours(24);
        public Duration getIdempotencyTtl() { return idempotencyTtl; }
        public void setIdempotencyTtl(Duration idempotencyTtl) { this.idempotencyTtl = idempotencyTtl; }
    }

    public static class Worker {
        private Pool processPool = new Pool(8, 16, 1000);
        private Pool imagegenPool = new Pool(4, 8, 200);
        private Duration heartbeatInterval = Duration.ofSeconds(30);
        public Pool getProcessPool() { return processPool; }
        public void setProcessPool(Pool processPool) { this.processPool = processPool; }
        public Pool getImagegenPool() { return imagegenPool; }
        public void setImagegenPool(Pool imagegenPool) { this.imagegenPool = imagegenPool; }
        public Duration getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        public Pool() { this(1, 1, 100); }
        public Pool(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }
        public int getCoreSize() { return coreSize; }
        public void setCoreSize(int coreSize) { this.coreSize = coreSize; }
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Lock {
        private Duration waitTime = Duration.ofSeconds(1);
        public Duration getWaitTime() { return waitTime; }
        public void setWaitTime(Duration waitTime) { this.waitTime = waitTime; }
    }

    public static class Recovery {
        private String cron = "0 */1 * * * *";
        private Duration staleAfter = Duration.ofMinutes(30);
        private int scanLimit = 100;
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public Duration getStaleAfter() { return staleAfter; }
        public void setStaleAfter(Duration staleAfter) { this.staleAfter = staleAfter; }
        public int getScanLimit() { return scanLimit; }
        public void setScanLimit(int scanLimit) { this.scanLimit = scanLimit; }
    }

    public static class Cancel {
        private Duration ttl = Duration.ofHours(1);
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    public static class Download {
        private Duration singleUrlExpiry = Duration.ofMinutes(15);
        private long maxBundleBytes = 2_147_483_648L;
        public Duration getSingleUrlExpiry() { return singleUrlExpiry; }
        public void setSingleUrlExpiry(Duration singleUrlExpiry) { this.singleUrlExpiry = singleUrlExpiry; }
        public long getMaxBundleBytes() { return maxBundleBytes; }
        public void setMaxBundleBytes(long maxBundleBytes) { this.maxBundleBytes = maxBundleBytes; }
    }

    public static class Terminal {
        private Duration judgeTimeout = Duration.ofSeconds(60);
        public Duration getJudgeTimeout() { return judgeTimeout; }
        public void setJudgeTimeout(Duration judgeTimeout) { this.judgeTimeout = judgeTimeout; }
    }

    public static class Progress {
        private Duration counterTtl = Duration.ofHours(1);
        public Duration getCounterTtl() { return counterTtl; }
        public void setCounterTtl(Duration counterTtl) { this.counterTtl = counterTtl; }
    }

    public static class Mq {
        private String topic = "pixflow-task";
        private String tag = "TASK_EXECUTE";
        private String consumerGroup = "pixflow-task-worker";
        private Duration sendTimeout = Duration.ofSeconds(5);
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
        public String getConsumerGroup() { return consumerGroup; }
        public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
        public Duration getSendTimeout() { return sendTimeout; }
        public void setSendTimeout(Duration sendTimeout) { this.sendTimeout = sendTimeout; }
    }
}
package com.pixflow.module.dag.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * dag 模块配置:对齐 dag.md §12 与 §十二.2。
 *
 * <p>承载校验护栏、组缓存生命周期、执行超时、共享素材缓存容量。
 */
@ConfigurationProperties(prefix = "pixflow.dag")
public class DagProperties {

    private Validate validate = new Validate();
    private GroupCache groupCache = new GroupCache();
    private Execution execution = new Execution();
    private AssetCache assetCache = new AssetCache();

    public Validate getValidate() {
        return validate;
    }

    public void setValidate(Validate validate) {
        this.validate = validate;
    }

    public GroupCache getGroupCache() {
        return groupCache;
    }

    public void setGroupCache(GroupCache groupCache) {
        this.groupCache = groupCache;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public AssetCache getAssetCache() {
        return assetCache;
    }

    public void setAssetCache(AssetCache assetCache) {
        this.assetCache = assetCache;
    }

    public static class Validate {
        /** 节点数上限(设计文档 §9.1 默认 50) */
        private int maxNodes = 50;

        /** 节点数下限 */
        private int minNodes = 1;

        public int getMaxNodes() {
            return maxNodes;
        }

        public void setMaxNodes(int maxNodes) {
            this.maxNodes = maxNodes;
        }

        public int getMinNodes() {
            return minNodes;
        }

        public void setMinNodes(int minNodes) {
            this.minNodes = minNodes;
        }
    }

    public static class GroupCache {
        /** 组支路成员预处理引用 TTL(state.md 强制 TTL) */
        private Duration refTtl = Duration.ofHours(2);

        public Duration getRefTtl() {
            return refTtl;
        }

        public void setRefTtl(Duration refTtl) {
            this.refTtl = refTtl;
        }
    }

    public static class Execution {
        /** 单支路总超时(含 remove_bg+resize+encode+put 全链路) */
        private Duration unitTimeout = Duration.ofSeconds(60);

        /** 单节点超时(主要针对 remove_bg) */
        private Duration perNodeTimeout = Duration.ofSeconds(30);

        /** generate_copy 文案生成超时 */
        private Duration copyTimeout = Duration.ofSeconds(30);

        /** 源字节防护阈值(超过即 DAG_SOURCE_BYTES_TOO_LARGE → SKIP) */
        private long sourceBytesLimit = 209715200L; // 200MB

        public Duration getUnitTimeout() {
            return unitTimeout;
        }

        public void setUnitTimeout(Duration unitTimeout) {
            this.unitTimeout = unitTimeout;
        }

        public Duration getPerNodeTimeout() {
            return perNodeTimeout;
        }

        public void setPerNodeTimeout(Duration perNodeTimeout) {
            this.perNodeTimeout = perNodeTimeout;
        }

        public Duration getCopyTimeout() {
            return copyTimeout;
        }

        public void setCopyTimeout(Duration copyTimeout) {
            this.copyTimeout = copyTimeout;
        }

        public long getSourceBytesLimit() {
            return sourceBytesLimit;
        }

        public void setSourceBytesLimit(long sourceBytesLimit) {
            this.sourceBytesLimit = sourceBytesLimit;
        }
    }

    public static class AssetCache {
        /** 任务级 watermark 缓存容量上限 */
        private int maxEntriesPerTask = 5;

        /** 关闭后每次重新 decode(仅排障用) */
        private boolean enabled = true;

        public int getMaxEntriesPerTask() {
            return maxEntriesPerTask;
        }

        public void setMaxEntriesPerTask(int maxEntriesPerTask) {
            this.maxEntriesPerTask = maxEntriesPerTask;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

package com.etherealstar.pixflow.module.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 素材管理（Asset_Manager）相关阈值配置。
 *
 * <p>绑定 {@code pixflow.asset.*} 配置项，集中管理上传与解压相关的安全阈值（需求 1.3、1.4）：
 * <ul>
 *   <li>{@link #zipMaxSize}：上传 zip 体积上限（默认 500 MB，需求 1.3）；</li>
 *   <li>{@link #extractedMaxSize}：解压后累计文件总大小上限（默认 2 GB，zip-bomb 防护，需求 1.4）；</li>
 *   <li>{@link #extractedMaxCount}：解压后文件总数上限（默认 2000，zip-bomb 防护，需求 1.4）；</li>
 *   <li>{@link #docMaxSize}：文案文档体积上限（默认 50 MB，需求 3.2）；</li>
 *   <li>{@link #docMaxRows}：文案文档数据行数上限（默认 10000，需求 3.3）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "pixflow.asset")
public class AssetProperties {

    /** 上传 zip 体积上限（字节），默认 500 MB。 */
    private long zipMaxSize = 500L * 1024 * 1024;

    /** 解压后累计文件总大小上限（字节），默认 2 GB。 */
    private long extractedMaxSize = 2L * 1024 * 1024 * 1024;

    /** 解压后文件总数上限，默认 2000。 */
    private int extractedMaxCount = 2000;

    /** 文案文档体积上限（字节），默认 50 MB（需求 3.2）。 */
    private long docMaxSize = 50L * 1024 * 1024;

    /** 文案文档数据行数上限（不含表头），默认 10000（需求 3.3）。 */
    private int docMaxRows = 10000;

    public long getZipMaxSize() {
        return zipMaxSize;
    }

    public void setZipMaxSize(long zipMaxSize) {
        this.zipMaxSize = zipMaxSize;
    }

    public long getExtractedMaxSize() {
        return extractedMaxSize;
    }

    public void setExtractedMaxSize(long extractedMaxSize) {
        this.extractedMaxSize = extractedMaxSize;
    }

    public int getExtractedMaxCount() {
        return extractedMaxCount;
    }

    public void setExtractedMaxCount(int extractedMaxCount) {
        this.extractedMaxCount = extractedMaxCount;
    }

    public long getDocMaxSize() {
        return docMaxSize;
    }

    public void setDocMaxSize(long docMaxSize) {
        this.docMaxSize = docMaxSize;
    }

    public int getDocMaxRows() {
        return docMaxRows;
    }

    public void setDocMaxRows(int docMaxRows) {
        this.docMaxRows = docMaxRows;
    }
}

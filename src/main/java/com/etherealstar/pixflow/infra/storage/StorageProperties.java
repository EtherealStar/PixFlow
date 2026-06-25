package com.etherealstar.pixflow.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地文件存储配置。
 *
 * <p>绑定 {@code pixflow.storage.*} 配置项，提供存储根目录等设置。</p>
 */
@ConfigurationProperties(prefix = "pixflow.storage")
public class StorageProperties {

    /**
     * 存储根目录（绝对或相对工作目录）。所有相对路径均以此目录为基准解析。
     */
    private String root = "./data/pixflow-storage";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}

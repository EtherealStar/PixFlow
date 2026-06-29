package com.pixflow.module.dag.validate;

import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.propose.PendingPlanService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SchemaRegistryValidator:启动期自检(对齐 dag.md §6.2.1)。
 *
 * <p>扫描所有 schema 资源:
 * <ul>
 *   <li>每个 PixelTool 都有对应 schema 资源文件</li>
 *   <li>同 tool 的 schema 版本号单调递增(防回退)</li>
 *   <li>代码声明的当前大版本号 = 所有 schema 资源中声明的大版本号</li>
 * </ul>
 *
 * <p>启动期大版本升级时:调 {@link PendingPlanService#expireByOldSchema(String)}
 * 把所有 PENDING 的旧版本 plan 标 EXPIRED。
 */
@Component
public class SchemaRegistryValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryValidator.class);

    /** 当前大版本号(代码声明);启动期与 schema 资源 version 字段对齐校验。 */
    public static final String CURRENT_MAJOR_VERSION = "1";

    private final ParamSchemaRegistry schemaRegistry;
    private final PendingPlanService pendingPlanService;

    @Autowired
    public SchemaRegistryValidator(ParamSchemaRegistry schemaRegistry,
                                    PendingPlanService pendingPlanService) {
        this.schemaRegistry = schemaRegistry;
        this.pendingPlanService = pendingPlanService;
    }

    @PostConstruct
    public void validate() {
        // 校验 schema 资源 version 字段与代码 CURRENT_MAJOR_VERSION 对齐
        for (PixelTool tool : PixelTool.values()) {
            String version = schemaRegistry.schemaVersion(tool);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("工具 " + tool.wireName() + " 缺 schema version");
            }
            DagSchemaVersion dsv = new DagSchemaVersion(version);
            if (dsv.major() < Integer.parseInt(CURRENT_MAJOR_VERSION)) {
                // 资源版本落后于代码版本:启动期拒绝
                throw new IllegalStateException(
                    "工具 " + tool.wireName() + " schema version " + version
                        + " 低于当前大版本 " + CURRENT_MAJOR_VERSION
                        + ";升级后请同步删除旧 schema 资源");
            }
        }
        // 大版本升级时清旧版本 plan
        int expired = pendingPlanService.expireByOldSchema(CURRENT_MAJOR_VERSION);
        if (expired > 0) {
            log.warn("Schema 大版本升级至 {}:已把 {} 条旧版本 PENDING plan 标记 EXPIRED",
                CURRENT_MAJOR_VERSION, expired);
        } else {
            log.info("SchemaRegistryValidator: {} 个工具 schema 全部加载,当前大版本 {}",
                PixelTool.values().length, CURRENT_MAJOR_VERSION);
        }
    }
}
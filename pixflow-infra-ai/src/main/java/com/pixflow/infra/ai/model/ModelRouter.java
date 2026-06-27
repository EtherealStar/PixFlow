package com.pixflow.infra.ai.model;

/**
 * 逻辑角色到具体模型配置的解析器。
 */
public interface ModelRouter {
    ResolvedModel resolve(ModelRole role);
}

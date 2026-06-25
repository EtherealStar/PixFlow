package com.etherealstar.pixflow.infra.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Plus 配置。
 * <p>统一扫描各业务模块下的 {@code mapper} 包，并注册分页插件（列表查询需求 4.4、12.1、13.1 使用）。
 * <p>全库采用软关联，不使用数据库外键。
 */
@Configuration
@MapperScan("com.etherealstar.pixflow.module.**.mapper")
public class MyBatisPlusConfig {

    /**
     * MyBatis Plus 拦截器：注册分页内部拦截器（MySQL 方言）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大限制，超过该值的 size 不再放大查询（列表分页 size 上限 100，由业务层另行校验）
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}

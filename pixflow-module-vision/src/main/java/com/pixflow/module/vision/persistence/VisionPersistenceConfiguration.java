package com.pixflow.module.vision.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(SqlSessionFactory.class)
@MapperScan(basePackageClasses = VisionStateMapper.class, annotationClass = Mapper.class)
public class VisionPersistenceConfiguration {
}

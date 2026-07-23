package com.pixflow.module.vision.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = VisionStateMapper.class, annotationClass = Mapper.class)
public class VisionPersistenceConfiguration {
}

package com.pixflow.app;

import com.pixflow.common.error.render.HttpErrorRenderer;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(HttpErrorRenderer.class)
public class PixFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(PixFlowApplication.class, args);
    }

    @Bean
    public static MapperScannerConfigurer appMapperScannerConfigurer() {
        MapperScannerConfigurer configurer = new MapperScannerConfigurer();
        configurer.setBasePackage("com.pixflow.app");
        configurer.setAnnotationClass(Mapper.class);
        return configurer;
    }
}

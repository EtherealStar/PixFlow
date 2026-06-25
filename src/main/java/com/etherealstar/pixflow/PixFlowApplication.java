package com.etherealstar.pixflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PixFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(PixFlowApplication.class, args);
    }

}

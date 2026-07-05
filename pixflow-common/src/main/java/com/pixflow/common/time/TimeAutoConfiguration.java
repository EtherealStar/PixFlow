package com.pixflow.common.time;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class TimeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock pixflowClock() {
        // 全应用只保留一个平台时间源，测试可提供唯一 Clock Bean 覆盖这里。
        return Clock.systemUTC();
    }
}

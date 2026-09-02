package com.xmps.config;

import com.xmps.security.ApiKeyFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    // ============================================================
    // 配置属性 Bean
    // ============================================================

    @Bean
    @ConfigurationProperties(prefix = "xmps.security.api-key")
    public ApiKeyProperties apiKeyProperties() {
        return new ApiKeyProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "xmps.async")
    public AsyncProperties asyncProperties() {
        return new AsyncProperties();
    }

    // ============================================================
    // API Key 过滤器
    // ============================================================

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(ApiKeyProperties props) {
        List<String> keys = props.getKeys() != null && !props.getKeys().isBlank()
                ? Arrays.asList(props.getKeys().split("\\s*,\\s*"))
                : Collections.emptyList();

        List<String> excludePaths = List.of(
                "/actuator/**",
                "/h2-console/**",
                "/health",
                "/error"
        );

        ApiKeyFilter filter = new ApiKeyFilter(keys, props.getHeader(), excludePaths);

        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    // ============================================================
    // 异步任务执行器
    // ============================================================

    @Bean(name = "reviewTaskExecutor")
    public Executor reviewTaskExecutor(AsyncProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix(props.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

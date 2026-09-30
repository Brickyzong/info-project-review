package com.xmps.config;

import com.xmps.security.ApiKeyFilter;
import com.xmps.security.IpWhitelistFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.stream.Collectors;

@Configuration
@EnableAsync
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

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

    @Bean
    @ConfigurationProperties(prefix = "xmps.security.whitelist")
    public IpWhitelistProperties ipWhitelistProperties() {
        return new IpWhitelistProperties();
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
    // IP 白名单过滤器（order=0，先于 API Key 鉴权执行）
    // ============================================================

    @Bean
    public FilterRegistrationBean<IpWhitelistFilter> ipWhitelistFilterRegistration(IpWhitelistProperties props) {
        List<String> ips = (props.getAllowedIps() != null && !props.getAllowedIps().isBlank())
                ? Arrays.stream(props.getAllowedIps().split("\\s*,\\s*"))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList())
                : Collections.emptyList();

        List<String> excludePaths = List.of(
                "/actuator/**",
                "/h2-console/**",
                "/health",
                "/error"
        );

        IpWhitelistFilter filter = new IpWhitelistFilter(ips, excludePaths);
        FilterRegistrationBean<IpWhitelistFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        log.info("[IP白名单] 状态={}, 规则数={}",
                filter.isEnabled() ? "启用" : "关闭(allow all)", ips.size());
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

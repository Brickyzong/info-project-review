package com.xmps.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 回调重试配置
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "xmps.callback")
public class CallbackProperties {
    private int maxRetries = 5;
    private int retryIntervalSeconds = 60;
    private int initialDelaySeconds = 5;
}

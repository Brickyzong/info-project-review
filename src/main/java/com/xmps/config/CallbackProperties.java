package com.xmps.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 回调重试配置（对齐技术方案文档：失败间隔 10s/30s/60s，重试 3 次）
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "xmps.callback")
public class CallbackProperties {
    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试间隔梯度（秒），第 N 次失败后等待 retryIntervals[N-1]
     */
    private List<Integer> retryIntervals = Arrays.asList(10, 30, 60);
    /**
     * 首次推送前的初始延迟（秒）
     */
    private int initialDelaySeconds = 2;
}

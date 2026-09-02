package com.xmps.config;

import lombok.Getter;
import lombok.Setter;

/**
 * LLM 连接池配置
 */
@Getter
@Setter
public class LlmPoolProperties {
    private int maxConnections = 10;
    private int maxIdle = 5;
    private int keepAliveMinutes = 5;
}

package com.xmps.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 客户端配置属性
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "xmps.llm")
public class LlmProperties {
    /**
     * 协议类型：openai-compatible
     */
    private String provider = "openai-compatible";
    /**
     * API 地址
     */
    private String baseUrl = "https://api.deepseek.com/v1";
    /**
     * API Key（支持环境变量 LLM_API_KEY 覆盖）
     */
    private String apiKey;
    /**
     * 模型名称
     */
    private String model = "deepseek-chat";
    /**
     * 超时（秒）
     */
    private int timeoutSeconds = 120;
    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 连接池
     */
    private LlmPoolProperties pool = new LlmPoolProperties();
}

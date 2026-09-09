package com.xmps.config;

import lombok.Getter;
import lombok.Setter;

/**
 * API Key 鉴权配置属性
 */
@Getter
@Setter
public class ApiKeyProperties {
    /**
     * HTTP 请求头名称（技术方案文档要求 Authorization: Bearer {api_key}）
     */
    private String header = "Authorization";
    /**
     * 有效 Key 列表（逗号分隔）
     */
    private String keys = "";
}

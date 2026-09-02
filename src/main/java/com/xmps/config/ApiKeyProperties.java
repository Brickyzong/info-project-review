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
     * HTTP 请求头名称
     */
    private String header = "X-API-Key";
    /**
     * 有效 Key 列表（逗号分隔）
     */
    private String keys = "";
}

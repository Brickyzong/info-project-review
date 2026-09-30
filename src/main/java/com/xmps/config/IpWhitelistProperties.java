package com.xmps.config;

import lombok.Getter;
import lombok.Setter;

/**
 * IP 白名单配置属性（应用层安全加固）。
 * 留空（默认）表示关闭白名单，放行所有来源——避免破坏既有集成测试与本地调试。
 * 生产环境通过环境变量 XMPS_ALLOWED_IPS 注入逗号分隔的 IP / CIDR 列表。
 */
@Getter
@Setter
public class IpWhitelistProperties {
    /**
     * 允许的客户端 IP 列表，逗号分隔。
     * 支持精确 IP（如 10.0.0.5）与 CIDR（如 192.168.1.0/24、10.0.0.0/8）。
     * 留空 = 关闭白名单。
     */
    private String allowedIps = "";
}

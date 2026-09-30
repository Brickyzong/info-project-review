package com.xmps.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * IP 白名单过滤器（应用层安全加固）。
 *
 * <p>行为：
 * <ul>
 *   <li>当 allowedIps 为空（未配置）时，白名单<strong>关闭</strong>，放行所有来源（避免破坏既有集成测试）。</li>
 *   <li>配置后，仅允许白名单内的客户端 IP（支持精确 IP 与 CIDR，如 192.168.1.0/24）通过；其余返回 403。</li>
 *   <li>排除路径（健康检查、H2 控制台、error）不校验，保证探针与运维通道可用。</li>
 *   <li>来源 IP 解析顺序：X-Forwarded-For（取最左，即原始客户端）→ X-Real-IP → getRemoteAddr()。</li>
 * </ul>
 *
 * <p>注意：X-Forwarded-For 可被客户端伪造。仅当服务前置<strong>已知可信</strong>的反向代理 / 政务网关时才依赖它；
 * 若服务直接暴露公网，请勿信任该头，应改用 getRemoteAddr()（可在解析逻辑中关闭 XFF 支持）。
 */
public class IpWhitelistFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(IpWhitelistFilter.class);

    private final List<String> allowedIps;
    private final List<String> excludePaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<CidrRange> ranges = new ArrayList<>();

    public IpWhitelistFilter(List<String> allowedIps, List<String> excludePaths) {
        this.allowedIps = allowedIps == null ? List.of() : allowedIps;
        this.excludePaths = excludePaths == null ? List.of() : excludePaths;
        if (!this.allowedIps.isEmpty()) {
            for (String raw : this.allowedIps) {
                String s = raw.trim();
                if (s.isEmpty()) continue;
                ranges.add(s.contains("/") ? CidrRange.parse(s) : CidrRange.exact(s));
            }
        }
    }

    public boolean isEnabled() {
        return !allowedIps.isEmpty();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var req = (HttpServletRequest) request;
        var res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // 白名单未启用 → 直接放行
        if (!isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // 排除路径（探针 / 控制台）不校验
        if (excludePaths.stream().anyMatch(p -> pathMatcher.match(p, path))) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(req);
        if (isAllowed(clientIp)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("IP 不在白名单 — path={}, ip={}", path, clientIp);
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":403,\"message\":\"客户端 IP 不在白名单内\"}");
    }

    /**
     * 来源 IP 解析：优先 X-Forwarded-For 最左段（原始客户端），其次 X-Real-IP，最后回退到 TCP 对端地址。
     */
    private String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return req.getRemoteAddr();
    }

    private boolean isAllowed(String clientIp) {
        for (CidrRange r : ranges) {
            if (r.matches(clientIp)) return true;
        }
        return false;
    }

    // ===================== CIDR / 精确匹配 =====================

    private static final class CidrRange {
        final InetAddress address;
        final int prefix;     // 仅 CIDR 有效；精确匹配时置 -1
        final boolean exact;

        CidrRange(InetAddress address, int prefix, boolean exact) {
            this.address = address;
            this.prefix = prefix;
            this.exact = exact;
        }

        static CidrRange exact(String ip) {
            try {
                return new CidrRange(InetAddress.getByName(ip), -1, true);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("非法 IP: " + ip, e);
            }
        }

        static CidrRange parse(String cidr) {
            int idx = cidr.indexOf('/');
            String ipPart = cidr.substring(0, idx).trim();
            int prefix = Integer.parseInt(cidr.substring(idx + 1).trim());
            try {
                return new CidrRange(InetAddress.getByName(ipPart), prefix, false);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("非法 CIDR: " + cidr, e);
            }
        }

        boolean matches(String ipStr) {
            InetAddress client;
            try {
                client = InetAddress.getByName(ipStr);
            } catch (UnknownHostException e) {
                return false;
            }
            // IPv4 与 IPv6 类型不一致直接判否
            if (!address.getClass().equals(client.getClass())) {
                return false;
            }
            if (exact) {
                return address.getHostAddress().equalsIgnoreCase(client.getHostAddress());
            }
            byte[] a = address.getAddress();
            byte[] c = client.getAddress();
            int bits = prefix;
            for (int i = 0; i < a.length && bits > 0; i++) {
                int mask = (bits >= 8) ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((a[i] & mask) != (c[i] & mask)) return false;
                bits -= 8;
            }
            return true;
        }
    }
}

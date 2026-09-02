package com.xmps.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

/**
 * API Key 鉴权过滤器。
 * 健康检查、H2 控制台等路径不校验。
 */
public class ApiKeyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final List<String> validKeys;
    private final String headerName;
    private final List<String> excludePaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiKeyFilter(List<String> validKeys, String headerName, List<String> excludePaths) {
        this.validKeys = validKeys;
        this.headerName = headerName;
        this.excludePaths = excludePaths;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var req = (HttpServletRequest) request;
        var res = (HttpServletResponse) response;

        String path = req.getRequestURI();

        // 跳过排除路径
        if (excludePaths.stream().anyMatch(p -> pathMatcher.match(p, path))) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = req.getHeader(headerName);
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("缺少 API Key — path={}, ip={}", path, req.getRemoteAddr());
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":401,\"message\":\"缺少 API Key，请在 X-API-Key 请求头中提供\"}");
            return;
        }

        if (!validKeys.contains(apiKey)) {
            log.warn("无效 API Key — path={}, ip={}", path, req.getRemoteAddr());
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":403,\"message\":\"API Key 无效\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}

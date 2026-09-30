package com.xmps.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * IpWhitelistFilter 单元测试（不依赖 Spring 容器，无 LLM 调用）。
 */
class IpWhitelistFilterTest {

    private static final List<String> EXCLUDES = List.of("/actuator/**", "/h2-console/**", "/health", "/error");

    private HttpServletResponse mockResponse(StringWriter body) throws Exception {
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(body, true));
        return res;
    }

    private HttpServletRequest mockRequest(String uri, String remoteAddr, String xff, String xri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        when(req.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(req.getHeader("X-Real-IP")).thenReturn(xri);
        return req;
    }

    @Test
    void disabledWhenEmptyAllowsAll() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter(List.of(), EXCLUDES);
        assertEquals(false, filter.isEnabled());

        StringWriter body = new StringWriter();
        HttpServletRequest req = mockRequest("/api/review/submit", "203.0.113.9", null, null);
        HttpServletResponse res = mockResponse(body);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(anyInt());
    }

    @Test
    void exactIpAllowedAndBlocked() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter(List.of("127.0.0.1"), EXCLUDES);

        // 命中白名单 → 放行
        StringWriter ok = new StringWriter();
        HttpServletRequest reqOk = mockRequest("/api/review/submit", "127.0.0.1", null, null);
        HttpServletResponse resOk = mockResponse(ok);
        FilterChain chainOk = mock(FilterChain.class);
        filter.doFilter(reqOk, resOk, chainOk);
        verify(chainOk).doFilter(reqOk, resOk);
        verify(resOk, never()).setStatus(anyInt());

        // 未命中 → 403
        StringWriter bad = new StringWriter();
        HttpServletRequest reqBad = mockRequest("/api/review/submit", "10.0.0.5", null, null);
        HttpServletResponse resBad = mockResponse(bad);
        FilterChain chainBad = mock(FilterChain.class);
        filter.doFilter(reqBad, resBad, chainBad);

        verify(chainBad, never()).doFilter(any(), any());
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(resBad).setStatus(status.capture());
        assertEquals(403, status.getValue());
        assertEquals("{\"code\":403,\"message\":\"客户端 IP 不在白名单内\"}", bad.toString().trim());
    }

    @Test
    void cidrMatch() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter(List.of("192.168.1.0/24"), EXCLUDES);

        // 网段内 → 放行
        StringWriter in = new StringWriter();
        HttpServletRequest reqIn = mockRequest("/api/review/submit", "192.168.1.100", null, null);
        HttpServletResponse resIn = mockResponse(in);
        FilterChain chainIn = mock(FilterChain.class);
        filter.doFilter(reqIn, resIn, chainIn);
        verify(chainIn).doFilter(reqIn, resIn);
        verify(resIn, never()).setStatus(anyInt());

        // 网段外 → 拒绝
        StringWriter out = new StringWriter();
        HttpServletRequest reqOut = mockRequest("/api/review/submit", "192.168.2.1", null, null);
        HttpServletResponse resOut = mockResponse(out);
        FilterChain chainOut = mock(FilterChain.class);
        filter.doFilter(reqOut, resOut, chainOut);
        verify(chainOut, never()).doFilter(any(), any());
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(resOut).setStatus(status.capture());
        assertEquals(403, status.getValue());
    }

    @Test
    void xForwardedForUsed() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter(List.of("203.0.113.7"), EXCLUDES);

        // XFF 最左为白名单 IP（经可信代理时 TCP 对端是网关，应以 XFF 为准）
        StringWriter ok = new StringWriter();
        HttpServletRequest reqOk = mockRequest("/api/review/submit", "10.0.0.1", "203.0.113.7, 10.0.0.1", null);
        HttpServletResponse resOk = mockResponse(ok);
        FilterChain chainOk = mock(FilterChain.class);
        filter.doFilter(reqOk, resOk, chainOk);
        verify(chainOk).doFilter(reqOk, resOk);
        verify(resOk, never()).setStatus(anyInt());

        // XFF 非白名单 → 拒绝
        StringWriter bad = new StringWriter();
        HttpServletRequest reqBad = mockRequest("/api/review/submit", "10.0.0.1", "198.51.100.9", null);
        HttpServletResponse resBad = mockResponse(bad);
        FilterChain chainBad = mock(FilterChain.class);
        filter.doFilter(reqBad, resBad, chainBad);
        verify(chainBad, never()).doFilter(any(), any());
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(resBad).setStatus(status.capture());
        assertEquals(403, status.getValue());
    }

    @Test
    void excludePathBypassesWhitelist() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter(List.of("127.0.0.1"), EXCLUDES);

        // 探针路径即使 IP 不在白名单也放行
        StringWriter body = new StringWriter();
        HttpServletRequest req = mockRequest("/actuator/health", "8.8.8.8", null, null);
        HttpServletResponse res = mockResponse(body);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(anyInt());
    }

    @Test
    void ipv6ExactMatch() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter(List.of("::1"), EXCLUDES);

        StringWriter body = new StringWriter();
        HttpServletRequest req = mockRequest("/api/review/submit", "0:0:0:0:0:0:0:1", null, null);
        HttpServletResponse res = mockResponse(body);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(anyInt());
    }
}

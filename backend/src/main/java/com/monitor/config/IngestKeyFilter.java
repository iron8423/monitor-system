package com.monitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ingest 接入共享密钥校验（M0-D7，2026-09-09）。
 *
 * <p>Demo 无独立网关：{@code /api/v1/ingest/**} 免 JWT，改验请求头 {@code X-Ingest-Key}。
 * 密钥来自配置 {@code monitor.ingest-key}（默认值见 application.yml，可用环境变量
 * {@code MONITOR_INGEST_KEY} 覆盖）。密钥缺失/不符 → 401；其余路径本过滤器不生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null")
public class IngestKeyFilter extends OncePerRequestFilter {

    public static final String INGEST_KEY_HEADER = "X-Ingest-Key";
    private static final String INGEST_PATH_PREFIX = "/api/v1/ingest/";

    @Value("${monitor.ingest-key:}")
    private String ingestKey;

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INGEST_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(INGEST_KEY_HEADER);
        if (ingestKey == null || ingestKey.isBlank() || !ingestKey.equals(provided)) {
            log.warn("ingest 密钥校验失败: uri={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.fail(401, "ingest 密钥缺失或不正确")));
            return;
        }
        chain.doFilter(request, response);
    }
}

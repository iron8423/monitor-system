package com.monitor.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.audit.service.DeniedWriteAuditor;
import com.monitor.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 已认证但无权限 → 403 JSON。
 *
 * <p><b>越权的写操作也要留痕</b>（P1-8）：交给 {@link DeniedWriteAuditor}，
 * 与控制器 advice 那条路径共用同一个实现——403 有两条产出路径，
 * 各写一份迟早在其中一条上漏记（见该类的类注释）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final DeniedWriteAuditor deniedWriteAuditor;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        deniedWriteAuditor.recordIfWriteAttempt();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(403, "无权限访问")));
    }

}

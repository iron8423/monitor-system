package com.monitor.auth.security;

import com.monitor.auth.entity.SysUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 无状态 JWT 过滤器：解析 Authorization: Bearer <token>，构建 SecurityContext。
 * 解析失败（签名/过期）不抛异常，交由认证入口点返回 401。
 * SSE（/api/v1/stream）因 EventSource 无法携带 Header，额外支持从 query 参数 token 取（M0-D8）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String BEARER_PREFIX = "Bearer ";
    /**
     * 仅这些路径允许 query token（避免 token 出现在普通 GET 的日志/来源里）：
     * SSE（EventSource）与影像内容（{@code <img>}）都无法携带请求头。
     */
    private static final String SSE_PATH_PREFIX = "/api/v1/stream";
    private static final String MEDIA_PATH_PREFIX = "/api/v1/media/";
    private static final String MEDIA_CONTENT_SUFFIX = "/content";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parse(token);
                String username = claims.getSubject();
                String role = claims.get(JwtUtil.CLAIM_ROLE, String.class);
                Long uid = Long.parseLong(claims.get(JwtUtil.CLAIM_UID, String.class));

                SysUser u = new SysUser();
                u.setId(uid);
                u.setUsername(username);
                u.setRole(role);
                SecurityUser principal = new SecurityUser(u);

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.debug("JWT 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        String uri = request.getRequestURI();
        if (uri.startsWith(SSE_PATH_PREFIX)
                || (uri.startsWith(MEDIA_PATH_PREFIX) && uri.endsWith(MEDIA_CONTENT_SUFFIX))) {
            return request.getParameter("token");
        }
        return null;
    }
}

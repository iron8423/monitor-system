package com.monitor.auth.security;

import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 过滤器：解析 Authorization: Bearer &lt;token&gt;，构建 SecurityContext。
 * 解析失败（签名/过期）不抛异常，交由认证入口点返回 401。
 * SSE（/api/v1/stream）因 EventSource 无法携带 Header，额外支持从 query 参数 token 取（M0-D8）。
 *
 * <h3>为什么要回库核对，而不是只信令牌里的 claims</h3>
 * <p>纯无状态地信 claims 时，「停用账号」「降权」「改密」在令牌剩下的寿命里（默认 24h）全部无效，
 * 而且 {@link SecurityUser#isEnabled()} 在 JWT 路径上永不参与判断——它只在登录那一刻被看过。
 * 于是删掉一个离职人员的账号，他手上的令牌还能继续用一整天。</p>
 *
 * <p>现在每次请求回库取一次该用户（主键查询），核对三件事：账号还在、{@code enabled} 仍为真、
 * 令牌版本与 {@code token_version} 一致；并且<b>用库中这一行构造主体</b>，所以角色取的是<b>当前</b>
 * 角色而不是签发时的旧角色——降权同样立即生效。</p>
 *
 * <p>代价是每个请求多一次主键查询。这个系统是单机内部部署，这笔开销换「权限变更立即生效」
 * 是划算的；真要优化就加短 TTL 缓存，但那样「立即」会变成「最多 N 秒后」，得先把延迟写进文档。</p>
 *
 * <p>注意这里刻意<b>不</b>把「查不到用户」和「版本不匹配」区分成不同的响应：两种情况都只是清掉
 * 上下文，由认证入口点统一回 401。区分开来等于告诉调用方「这个 token 的签名是对的、只是人没了」，
 * 那是白白多给一条信息。</p>
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
    private final SysUserMapper userMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parse(token);
                Long uid = Long.parseLong(claims.get(JwtUtil.CLAIM_UID, String.class));

                SysUser live = liveUser(uid, claims);
                if (live == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    SecurityUser principal = new SecurityUser(live);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                log.debug("JWT 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 该令牌现在是否仍然有效；有效则返回<b>库中当前</b>那一行用户，否则返回 {@code null}。
     *
     * <p>令牌里没有 {@code ver} 一律拒绝，不做事后兼容：升级到本版本之前的令牌都没有这个 claim，
     * 放行它们等于让整套机制在最长一个令牌周期内形同不存在。代价是升级后所有人重新登录一次，
     * 这一条已写进交付说明。</p>
     */
    private SysUser liveUser(Long uid, Claims claims) {
        Object rawVersion = claims.get(JwtUtil.CLAIM_VER);
        if (!(rawVersion instanceof Number tokenVersion)) {
            log.debug("令牌缺少 {} claim，按失效处理: uid={}", JwtUtil.CLAIM_VER, uid);
            return null;
        }
        SysUser u = userMapper.selectById(uid);   // 逻辑删除自动过滤：已删账号查不到
        if (u == null || !Boolean.TRUE.equals(u.getEnabled())) {
            log.debug("令牌对应的账号不存在或已停用: uid={}", uid);
            return null;
        }
        int current = u.getTokenVersion() == null ? 0 : u.getTokenVersion();
        if (current != tokenVersion.intValue()) {
            log.debug("令牌版本已过期: uid={} 令牌={} 当前={}", uid, tokenVersion, current);
            return null;
        }
        return u;
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

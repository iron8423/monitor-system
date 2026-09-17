package com.monitor.auth.security;

import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌失效机制（清单 12）的判据。
 *
 * <p>这一组钉住的是「令牌里的 claims 说了不算、库里的当前状态说了算」：
 * 停用、令牌版本变更、以及旧版令牌（没有 {@code ver}）都必须过不去，
 * 角色也必须取库里的当前值而不是签发时的旧值。改动前 {@link JwtAuthFilter} 全程不查库，
 * 这五条里有四条会红。</p>
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String SECRET = "jwt-auth-filter-test-secret-key-at-least-32-bytes";
    private static final long EXPIRATION = 86_400_000L;
    private static final long UID = 1001L;

    @Mock
    private SysUserMapper userMapper;

    private JwtUtil jwtUtil;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, EXPIRATION);
        filter = new JwtAuthFilter(jwtUtil, userMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesWithCurrentDatabaseIdentity() throws Exception {
        when(userMapper.selectById(UID)).thenReturn(user("operator", "OPERATOR", true, 0));

        MockFilterChain chain = doFilter(jwtUtil.generateToken(UID, "operator", "OPERATOR", 0));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "有效令牌应建立认证上下文");
        SecurityUser principal = (SecurityUser) auth.getPrincipal();
        assertEquals("operator", principal.getUsername());
        assertEquals(List.of("ROLE_OPERATOR"), authorities(auth));
        assertNotNull(chain.getRequest(), "无论认证成功与否，都必须继续过滤器链（由入口点决定 401）");
    }

    @Test
    void disabledAccountIsRejectedWithoutWaitingForTokenExpiry() throws Exception {
        when(userMapper.selectById(UID)).thenReturn(user("operator", "OPERATOR", false, 0));

        doFilter(jwtUtil.generateToken(UID, "operator", "OPERATOR", 0));

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "账号已停用，令牌剩余寿命再长也不能用");
    }

    @Test
    void deletedAccountIsRejected() throws Exception {
        // selectById 查不到（含被逻辑删除的行）时什么也不桩，Mockito 默认返回 null
        doFilter(jwtUtil.generateToken(UID, "operator", "OPERATOR", 0));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenWithStaleVersionIsRejected() throws Exception {
        // 令牌签发时版本是 0，之后该用户登出/改密把版本推到了 1
        when(userMapper.selectById(UID)).thenReturn(user("operator", "OPERATOR", true, 1));

        doFilter(jwtUtil.generateToken(UID, "operator", "OPERATOR", 0));

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "令牌版本落后于库中当前值，视为已作废");
    }

    @Test
    void tokenWithoutVersionClaimIsRejected() throws Exception {
        // 升级前签发的旧令牌没有 ver claim。刻意不桩 selectById：
        // 版本号要在查库**之前**就判掉，不该为了拒绝一条旧令牌去打一次库。
        doFilter(oldStyleTokenWithoutVersionClaim());

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "缺 ver claim 的旧令牌一律拒绝，否则机制在最长一个令牌周期内形同不存在");
        verify(userMapper, never()).selectById(UID);
    }

    @Test
    void roleComesFromDatabaseNotFromToken() throws Exception {
        // 令牌里写的是 ADMIN（签发时的角色），库里已经降成 ANALYST
        when(userMapper.selectById(UID)).thenReturn(user("operator", "ANALYST", true, 0));

        doFilter(jwtUtil.generateToken(UID, "operator", "ADMIN", 0));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(List.of("ROLE_ANALYST"), authorities(auth),
                "降权必须立即生效：主体取库中当前角色，而不是令牌里那个旧角色");
    }

    // ---------- 夹具 ----------

    private MockFilterChain doFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/points");
        request.addHeader("Authorization", JwtAuthFilter.BEARER_PREFIX + token);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    /** 复刻升级前 {@code JwtUtil} 的令牌形态：只有 sub/uid/role，没有 ver。 */
    private String oldStyleTokenWithoutVersionClaim() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject("operator")
                .claim(JwtUtil.CLAIM_UID, String.valueOf(UID))
                .claim(JwtUtil.CLAIM_ROLE, "OPERATOR")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXPIRATION))
                .signWith(key)
                .compact();
    }

    private static SysUser user(String username, String role, boolean enabled, int tokenVersion) {
        SysUser u = new SysUser();
        u.setId(UID);
        u.setUsername(username);
        u.setRole(role);
        u.setEnabled(enabled);
        u.setTokenVersion(tokenVersion);
        return u;
    }

    private static List<String> authorities(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}

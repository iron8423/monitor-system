package com.monitor.config;

import com.monitor.auth.security.JwtAuthFilter;
import com.monitor.auth.security.RestAccessDeniedHandler;
import com.monitor.auth.security.RestAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置：无状态 JWT 鉴权 + 方法级 {@code @PreAuthorize}。
 *
 * <p>放行：健康检查、认证、OpenAPI 文档、ingest（共享密钥），以及**开关打开时**的 H2 控制台；
 * 其余一律鉴权。SSE 的 JWT 从 query 参数取得，见 {@link JwtAuthFilter}。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final IngestKeyFilter ingestKeyFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** ingest 密钥过滤器只挂在 Security 链上，禁止 Boot 再自动注册为全局 Servlet 过滤器 */
    @Bean
    public FilterRegistrationBean<IngestKeyFilter> ingestKeyFilterRegistration(IngestKeyFilter filter) {
        FilterRegistrationBean<IngestKeyFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * @param h2ConsoleEnabled 读的就是 {@code spring.h2.console.enabled} 本身——
     *        放行规则跟着开关走，而不是各写一份。B-7 的漏洞形态正是「两处各写一份」：
     *        控制台开关在基础 profile 里为 true，放行写死在 SecurityConfig 里，
     *        于是切到 postgres profile 后控制台仍注册、且无需凭证即可进入。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    // 容器内部的 ERROR 转发必须放行。否则任何未捕获异常（500）在转发到
                    // /error 时会被下面的 anyRequest().authenticated() 拦下，对外伪装成
                    // 401「未登录或令牌失效」——真实的 500 就此失踪，排查方向被带偏。
                    // 实测入口：/h2-console 用一个 H2 不自带的 language 值时 NPE（H2 只带
                    // _text_zh_cn.prop，没有 _text_zh.prop），本该 500，却报 401。
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    // 注册与登录同属「还没有会话时就要能调」的入口；
                    // 注意 /auth/me 与 /auth/password **不在**这里——它们是「我的资料/口令」，
                    // 必须有有效会话，放行就等于谁都能改别人。
                    .requestMatchers("/api/v1/health", "/api/v1/auth/login", "/api/v1/auth/logout",
                            "/api/v1/auth/register").permitAll()
                    // ingest 免 JWT（无网关，改用 X-Ingest-Key 共享密钥，见 IngestKeyFilter）
                    .requestMatchers("/api/v1/ingest/**").permitAll();
                // 控制台关掉时连放行一起撤掉：否则哪天有人把开关拨回去调试，
                // 一个「忘了改回来」就恢复成无凭证可达。
                if (h2ConsoleEnabled) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                auth
                    // swagger 保留放行：联调期前端要读 OpenAPI，它只暴露接口形状（B-7 口径）
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                            "/v3/api-docs/**", "/webjars/**").permitAll()
                    // 其余（含 /api/v1/stream）一律鉴权；SSE 的 token 从 query 取（见 JwtAuthFilter）
                    .anyRequest().authenticated();
            })
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            // sameOrigin 而非 disable：H2 控制台的框架全部同源，SAMEORIGIN 够用，
            // 而 disable 是**全局**关掉点击劫持防护——为了一个调试页面把整个应用
            // 变成可被任意站点 iframe 嵌套，不值。
            .headers(headers -> headers.frameOptions(fo -> fo.sameOrigin()))
            .formLogin(fl -> fl.disable())
            .httpBasic(hb -> hb.disable())
            .addFilterBefore(ingestKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

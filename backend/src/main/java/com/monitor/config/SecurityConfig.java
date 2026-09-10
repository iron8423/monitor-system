package com.monitor.config;

import com.monitor.auth.security.JwtAuthFilter;
import com.monitor.auth.security.RestAccessDeniedHandler;
import com.monitor.auth.security.RestAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
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
 * <p>放行：健康检查、认证、H2 控制台、OpenAPI 文档、ingest（共享密钥）；其余一律鉴权。
 * SSE 的 JWT 从 query 参数取得，见 {@link JwtAuthFilter}。</p>
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 容器内部的 ERROR 转发必须放行。否则任何未捕获异常（500）在转发到
                // /error 时会被下面的 anyRequest().authenticated() 拦下，对外伪装成
                // 401「未登录或令牌失效」——真实的 500 就此失踪，排查方向被带偏。
                // 实测入口：/h2-console 用一个 H2 不自带的 language 值时 NPE（H2 只带
                // _text_zh_cn.prop，没有 _text_zh.prop），本该 500，却报 401。
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/health", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                // ingest 免 JWT（无网关，改用 X-Ingest-Key 共享密钥，见 IngestKeyFilter）
                .requestMatchers("/api/v1/ingest/**").permitAll()
                .requestMatchers("/h2-console/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**", "/webjars/**").permitAll()
                // 其余（含 /api/v1/stream）一律鉴权；SSE 的 token 从 query 取（见 JwtAuthFilter）
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .headers(headers -> headers.frameOptions(fo -> fo.disable()))
            .formLogin(fl -> fl.disable())
            .httpBasic(hb -> hb.disable())
            .addFilterBefore(ingestKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

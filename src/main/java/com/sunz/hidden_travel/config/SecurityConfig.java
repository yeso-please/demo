package com.sunz.hidden_travel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpMethod;

/**
 * 폼 로그인 설정. 로그인 아이디는 이메일.
 *
 * 공개/비공개 구분 기준:
 *  - 탐색(지도·지역·후기 읽기)은 로그인 없이 볼 수 있어야 발견 서비스로 기능한다.
 *  - 내 것을 만들고 남기는 행위(코스 저장·후기 작성·프로필)만 로그인을 요구한다.
 *
 * 소셜 로그인 확장 시: oauth2-client 의존성을 추가하고 .oauth2Login() 을 덧붙이면 된다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CSRF 토큰을 요청 시작 시점에 즉시 만든다(기본은 실제로 쓰일 때까지 지연).
     *
     * 코스 만들기 화면처럼 HTML 이 큰 페이지는 폼이 나오기 전에 응답 버퍼가 이미
     * 전송돼버려서, 그 시점에 토큰을 만들려고 하면 세션을 생성할 수 없어
     * "Cannot create a session after the response has been committed" 로 렌더가 깨진다.
     * 지연 로딩을 끄면 세션·토큰이 렌더 전에 준비된다.
     */
    private CsrfTokenRequestAttributeHandler eagerCsrfHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // --- 정적 리소스 / 업로드 이미지 ---
                .requestMatchers("/css/**", "/js/**", "/geo/**", "/images/**", "/uploads/**",
                                 "/favicon.ico", "/error").permitAll()
                // --- 로그인 없이 볼 수 있는 화면 ---
                .requestMatchers("/", "/login", "/signup", "/onboarding", "/onboarding/**", "/map", "/trip/**", "/trips/**",
                                 "/region", "/region/**", "/reviews", "/chat").permitAll()
                // 다이어리 상세는 공개 편이면 누구나 읽는다(비공개 편은 서비스가 걸러낸다)
                .requestMatchers(HttpMethod.GET, "/diary/*").permitAll()
                // 공유받은 사람은 대개 비로그인이다 — 로그인 벽을 세우면 공유가 성립하지 않는다
                .requestMatchers(HttpMethod.GET, "/u/*").permitAll()
                // 내 발견 지도는 세션에 담긴 방문 이력만 보여준다 — 온보딩이 로그인을 요구하지 않으므로
                // 이 화면도 열려 있어야 한다. 아래 "/my/**" 인증 규칙보다 먼저 와야 적용된다.
                .requestMatchers("/my/discoveries", "/nearby").permitAll()
                .requestMatchers(HttpMethod.GET, "/review/*").permitAll()   // 후기 상세(공유 링크)
                // 코스 만들기는 로그인 없이 둘러보고 담아볼 수 있다(저장할 때만 로그인).
                // 담은 내용은 course.js 가 sessionStorage 에 보관했다가 로그인 후 복원한다.
                .requestMatchers(HttpMethod.GET, "/course").permitAll()
                .requestMatchers("/api/**").permitAll()                     // AI 추천 등 조회성 API
                // --- 개발 편의 (운영 전환 시 반드시 제거) ---
                .requestMatchers("/h2-console/**", "/admin/**").permitAll()
                // --- 로그인 필요 ---
                .requestMatchers(HttpMethod.POST, "/course/save").authenticated()
                .requestMatchers("/course/saved", "/my/**", "/profile/**",
                                 "/review/new", "/review").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")            // 편지 첫 화면과 로그인 화면 분리
                .loginProcessingUrl("/login")   // 폼 action
                .usernameParameter("email")
                .passwordParameter("password")
                .defaultSuccessUrl("/map", true)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                // CSRF 가 켜져 있으므로 기본적으로 POST /logout 만 받는다
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            // 폼은 Thymeleaf 가 CSRF 토큰을 자동으로 넣는다.
            // 아래 둘은 폼이 아니므로 제외: /api 는 조회성 JSON, h2-console 은 개발 도구.
            .csrf(csrf -> csrf
                .csrfTokenRequestHandler(eagerCsrfHandler())
                .ignoringRequestMatchers("/api/**", "/h2-console/**", "/admin/**"))
            // H2 콘솔이 frame 을 쓰므로 동일 출처 허용
            .headers(h -> h.frameOptions(f -> f.sameOrigin()));

        return http.build();
    }
}

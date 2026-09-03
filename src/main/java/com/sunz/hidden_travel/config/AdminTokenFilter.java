package com.sunz.hidden_travel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /admin/** 는 로그인 사용자 개념(ROLE_USER 하나뿐, 관리자 역할이 없음)과 별개로
 * 헤더 토큰으로 막는다. SecurityConfig 가 이 경로를 permitAll 로 열어둔 것과 무관하게
 * 항상 검사한다.
 *
 * admin.token 이 비어 있으면(로컬 기본값) 통과시킨다 — 로컬 개발 편의를 위해서다.
 * 배포 환경은 ADMIN_TOKEN 환경변수를 반드시 채워야 실질적으로 막힌다.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    @Value("${admin.token:}")
    private String adminToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!request.getRequestURI().startsWith("/admin/") || adminToken.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (!adminToken.equals(request.getHeader("X-Admin-Token"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "관리자 토큰이 필요합니다 (X-Admin-Token 헤더)");
            return;
        }

        chain.doFilter(request, response);
    }
}

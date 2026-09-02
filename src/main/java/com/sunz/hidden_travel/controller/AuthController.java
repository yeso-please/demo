package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.domain.AppUser;
import com.sunz.hidden_travel.user.AccountService;
import com.sunz.hidden_travel.user.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 회원가입 화면과 처리.
 * (로그인/로그아웃 자체는 Spring Security 필터가 처리하므로 여기엔 없다 —
 *  로그인 화면 라우팅은 PageController 가 담당)
 */
@Controller
public class AuthController {

    private final AccountService accountService;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/signup")
    public String signupForm() {
        return "signup";
    }

    /** 가입 후 바로 로그인 상태로 만들어 온보딩으로 보낸다(다시 로그인시키지 않는다) */
    @PostMapping("/signup")
    public String signup(@RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false) String confirmPassword,
                         @RequestParam String nickname,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         Model model) {
        AppUser user;
        try {
            user = accountService.signup(email, password, confirmPassword, nickname);
        } catch (AccountService.SignupException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("nickname", nickname);
            return "signup";
        }
        autoLogin(user, request, response);
        // 가입 직후 첫 화면은 다이어리다. 12문항 MBTI 는 취향을 묻기만 하고
        // 아무것도 보여주지 못한 채 화면 하나를 더 쓴다 — 첫 가치까지의 거리를 늘린다.
        // MBTI 검사는 프로필에서 선택적으로 남겨 둔다.
        return "redirect:/onboarding/visits";
    }

    /** 가입 직후 자동 로그인 — 세션에 인증 정보를 심는다 */
    private void autoLogin(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        AppUserDetails principal = new AppUserDetails(user);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}

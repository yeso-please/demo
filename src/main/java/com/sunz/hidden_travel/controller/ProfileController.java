package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.domain.AppUser;
import com.sunz.hidden_travel.service.ReviewService;
import com.sunz.hidden_travel.storage.ImageStorage;
import com.sunz.hidden_travel.user.AccountService;
import com.sunz.hidden_travel.user.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 프로필 조회 / 수정.
 * 내가 만든 코스·후기 수를 함께 보여 활동 기록의 허브 역할을 한다.
 */
@Controller
public class ProfileController {

    private static final String PROFILE_FOLDER = "profiles";

    private final CurrentUserService currentUserService;
    private final AccountService accountService;
    private final ReviewService reviewService;
    private final ImageStorage imageStorage;

    public ProfileController(CurrentUserService currentUserService,
                             AccountService accountService,
                             ReviewService reviewService,
                             ImageStorage imageStorage) {
        this.currentUserService = currentUserService;
        this.accountService = accountService;
        this.reviewService = reviewService;
        this.imageStorage = imageStorage;
    }

    @GetMapping("/profile")
    public String profile(Model model, HttpSession session) {
        AppUser user = currentUserService.current();
        if (user == null) {
            return "redirect:/";
        }
        fillModel(model, user, session);
        return "profile";
    }

    /** 프로필 수정 (닉네임·소개·사진) */
    @PostMapping("/profile")
    public String update(@RequestParam String nickname,
                         @RequestParam(required = false) String bio,
                         @RequestParam(required = false) MultipartFile profileImage,
                         RedirectAttributes ra) {
        Long userId = currentUserService.currentId();
        if (userId == null) {
            return "redirect:/";
        }
        // 사진은 새로 올렸을 때만 교체한다(빈 파일이면 null → 기존 유지)
        String imagePath = imageStorage.saveOne(profileImage, PROFILE_FOLDER);
        try {
            accountService.updateProfile(userId, nickname, bio, imagePath);
            ra.addFlashAttribute("message", "프로필을 저장했어요.");
        } catch (AccountService.SignupException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile";
    }

    /** 비밀번호 변경 */
    @PostMapping("/profile/password")
    public String changePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam(required = false) String confirmPassword,
                                 RedirectAttributes ra) {
        Long userId = currentUserService.currentId();
        if (userId == null) {
            return "redirect:/";
        }
        try {
            accountService.changePassword(userId, currentPassword, newPassword, confirmPassword);
            ra.addFlashAttribute("message", "비밀번호를 변경했어요.");
        } catch (AccountService.SignupException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile";
    }

    private void fillModel(Model model, AppUser user, HttpSession session) {
        model.addAttribute("user", user);
        // 다이어리는 아직 세션에만 있다(Visit 엔티티 없음) — 로그인해도 브라우저를 닫으면 사라진다.
        // 저장소가 붙으면 이 줄만 사용자 기준 조회로 바꾸면 된다.
        Object visits = session.getAttribute(VisitOnboardingController.SESSION_VISITS);
        model.addAttribute("visitCount", visits instanceof java.util.List<?> l ? l.size() : 0);
        model.addAttribute("courseCount", reviewService.myCourseCards(user.getId()).size());
        model.addAttribute("reviewCount", reviewService.myReviewCount(user.getId()));
    }
}

package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.ReviewDetail;
import com.sunz.hidden_travel.domain.Review;
import com.sunz.hidden_travel.domain.SavedCourse;
import com.sunz.hidden_travel.domain.SavedCourseStop;
import com.sunz.hidden_travel.service.ReviewService;
import com.sunz.hidden_travel.user.CurrentUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 여행 후기 작성 / 상세 / 피드.
 *
 * 동선: 내 코스 목록 → 후기 작성(/review/new) → 저장 → 후기 상세(/review/{id}, 공유 대상)
 *       후기 피드(/reviews) → 남의 후기 → 그 지역 상세로 이동(탐색 루프)
 */
@Controller
public class ReviewController {

    private final ReviewService reviewService;
    private final CurrentUserService currentUserService;

    public ReviewController(ReviewService reviewService, CurrentUserService currentUserService) {
        this.reviewService = reviewService;
        this.currentUserService = currentUserService;
    }

    /** 후기 작성 화면 (이미 쓴 후기가 있으면 내용을 채워 수정 모드로) */
    @GetMapping("/review/new")
    public String form(@RequestParam(required = false) Long courseId, Model model) {
        if (courseId == null) {
            // courseId 없이 직접 접근 → 400 빈 화면 대신 목록으로 보낸다
            return "redirect:/my/courses";
        }
        Long userId = currentUserService.currentId();
        SavedCourse course = reviewService.courseForWriting(courseId, userId);
        if (course == null) {
            // 없는 코스거나 내 코스가 아님 → 목록으로
            return "redirect:/my/courses";
        }

        Review existing = reviewService.existingReview(courseId);
        model.addAttribute("courseId", course.getId());
        model.addAttribute("courseTitle", course.getTitle());
        model.addAttribute("regionLabel", reviewService.regionLabel(course.getSigCd()));
        model.addAttribute("stopNames", course.getStops().stream().map(SavedCourseStop::getName).toList());
        model.addAttribute("content", existing != null ? existing.getContent() : "");
        model.addAttribute("shared", existing == null || existing.isShared());
        model.addAttribute("existingPhotos", existing != null ? existing.getPhotoPaths() : List.of());
        model.addAttribute("editing", existing != null);
        return "review-form";
    }

    /** 후기 저장 → 상세(공유 가능한 페이지)로 이동 */
    @PostMapping("/review")
    public String submit(@RequestParam(required = false) Long courseId,
                         @RequestParam(required = false) String content,
                         @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
                         @RequestParam(defaultValue = "false") boolean shared) {
        if (courseId == null) {
            return "redirect:/my/courses";
        }
        Long userId = currentUserService.currentId();
        Review saved = reviewService.write(userId, courseId, content, photos, shared);
        if (saved == null) {
            // 본문이 비었거나 코스를 찾지 못함 → 작성 화면으로 되돌린다
            return "redirect:/review/new?courseId=" + courseId;
        }
        return "redirect:/review/" + saved.getId();
    }

    /** 후기 상세 — 공유 링크가 가리키는 페이지 */
    @GetMapping("/review/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Long userId = currentUserService.currentId();
        ReviewDetail detail = reviewService.detail(id, userId);
        if (detail == null) {
            return "redirect:/reviews";
        }
        model.addAttribute("review", detail);
        return "review-detail";
    }

    /*
     * 피드(/reviews)는 DiaryController 로 옮겼다.
     * 후기와 다이어리를 합친 뒤로 읽을 것은 '다이어리 한 편'이지 '코스에 대한 후기'가 아니다.
     * 옛 후기도 다이어리로 옮겨져 있으므로 피드에서 빠지지 않는다.
     */
}

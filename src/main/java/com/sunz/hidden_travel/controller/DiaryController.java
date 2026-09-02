package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.DiaryDetail;
import com.sunz.hidden_travel.controller.dto.PublicMap;
import com.sunz.hidden_travel.service.DiaryService;
import com.sunz.hidden_travel.user.CurrentUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 다이어리 피드와 상세.
 *
 * <p>다른 사람의 여행 글을 읽는 자리다. 예전 '후기 피드'가 있던 곳이고 경로도 그대로 두었다 —
 * 읽는 사람 입장에서 달라진 건 <b>읽을 것이 코스 평가에서 여행 글로 바뀌었다</b>는 점이다.
 */
@Controller
public class DiaryController {

    private final DiaryService diaryService;
    private final CurrentUserService currentUserService;

    public DiaryController(DiaryService diaryService, CurrentUserService currentUserService) {
        this.diaryService = diaryService;
        this.currentUserService = currentUserService;
    }

    /** 피드 — 공개된 다이어리를 최신순으로 */
    @GetMapping("/reviews")
    public String feed(Model model) {
        model.addAttribute("diaries", diaryService.feed());
        return "diary-feed";
    }

    /** 전국 시군구 수 — '250곳 중 N곳' 표시에 쓴다 */
    private static final int TOTAL_REGIONS = 250;

    /**
     * 남의 켜진 지도 — <b>공유의 단위</b>.
     *
     * <p>코스를 공유하면 정보고, 지도를 공유하면 사람이다.
     * 불의 모양만 봐도 "이 사람은 바닷가만 다녔네"가 읽힌다.
     */
    @GetMapping("/u/{nickname}")
    public String publicMap(@PathVariable String nickname, Model model) {
        PublicMap map = diaryService.publicMap(nickname, TOTAL_REGIONS);
        if (map == null) {
            return "redirect:/reviews";
        }
        model.addAttribute("map", map);
        model.addAttribute("mine", nickname.equals(currentNickname()));
        return "public-map";
    }

    private String currentNickname() {
        var me = currentUserService.current();
        return me == null ? null : me.getNickname();
    }

    /** 한 편 읽기. 비공개 편은 작성자에게만 보인다 */
    @GetMapping("/diary/{id}")
    public String detail(@PathVariable Long id, Model model) {
        DiaryDetail detail = diaryService.detail(id, currentUserService.currentId());
        if (detail == null) {
            return "redirect:/reviews";
        }
        model.addAttribute("diary", detail);
        return "diary-detail";
    }
}

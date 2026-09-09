package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.domain.AppUser;
import com.sunz.hidden_travel.mbti.TravelMbtiService;
import com.sunz.hidden_travel.mbti.TravelMbtiType;
import com.sunz.hidden_travel.repository.AppUserRepository;
import com.sunz.hidden_travel.service.ExperienceTags;
import com.sunz.hidden_travel.service.TravelPreferenceService;
import com.sunz.hidden_travel.user.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 온보딩 = 여행 MBTI 12문항 + 경험 태그·제외 조건.
 *
 * <p>가입 직후 흐름에 놓이지만 로그인 없이도 끝까지 해볼 수 있다.
 * 비로그인이면 결과를 세션에 담아 지도·코스 생성까지 이어 쓰고, 저장은 가입한 뒤에 하도록 안내한다.
 *
 * <p>유형과 태그는 쓰임이 다르다 — 유형은 사람의 결(코스 밀도·동행 조율),
 * 태그는 장소를 고르는 값이다. 그래서 둘을 함께 받는다(PRD F-01).
 */
@Controller
public class OnboardingController {

    private final TravelMbtiService mbtiService;
    private final CurrentUserService currentUserService;
    private final AppUserRepository appUserRepository;
    private final TravelPreferenceService preferenceService;

    public OnboardingController(TravelMbtiService mbtiService,
                                CurrentUserService currentUserService,
                                AppUserRepository appUserRepository,
                                TravelPreferenceService preferenceService) {
        this.mbtiService = mbtiService;
        this.currentUserService = currentUserService;
        this.appUserRepository = appUserRepository;
        this.preferenceService = preferenceService;
    }

    @GetMapping("/onboarding")
    public String onboarding(Model model) {
        AppUser me = currentUserService.current();
        model.addAttribute("questions", mbtiService.questions());
        model.addAttribute("loggedIn", me != null);
        // 문항 위에 붙는 라벨 — 무엇을 재는 문항인지 알려주면 답을 고르기가 쉬워진다
        model.addAttribute("axisLabels", Map.of(
                "JP", "일정", "SN", "보는 방식", "TF", "판단", "EI", "에너지"));
        // 태그 사전은 추천이 실제로 쓰는 것과 같은 목록이어야 한다 — 화면에서 따로 정의하지 않는다
        model.addAttribute("experienceTags", ExperienceTags.all());
        model.addAttribute("excludeOptions", TravelPreferenceService.EXCLUDE_OPTIONS);
        model.addAttribute("pickedTags", me != null ? me.experienceTagList() : List.of());
        model.addAttribute("pickedExcludes", me != null ? me.excludeTagList() : List.of());
        return "onboarding";
    }

    /**
     * 답안 제출 → 유형 계산 + 성향 보관.
     *
     * <p>요청 본문: {@code {answers:[1,2,...], tags:[...], excludes:[...]}}
     * 로그인 상태면 계정에, 아니면 세션에 담는다.
     */
    @PostMapping("/api/mbti/result")
    @ResponseBody
    @Transactional
    public Map<String, Object> result(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        List<Integer> answers = readInts(body, "answers");
        TravelMbtiType type = mbtiService.score(answers);
        if (type == null) {
            return Map.of("error", "답변이 올바르지 않아요. 다시 시도해 주세요.");
        }

        List<String> tags = preferenceService.normalizeTags(readStrings(body, "tags"));
        List<String> excludes = preferenceService.normalizeExcludes(readStrings(body, "excludes"));

        boolean saved = false;
        Long userId = currentUserService.currentId();
        if (userId != null) {
            AppUser user = appUserRepository.findById(userId).orElse(null);
            if (user != null) {
                user.setTravelMbti(type.getCode());
                user.setExperienceTags(String.join(",", tags));
                user.setExcludeTags(String.join(",", excludes));
                saved = true;
            }
        }
        // 비로그인이어도 이번 세션의 코스 생성에는 그대로 쓴다(로그인하면 계정으로 옮긴다)
        preferenceService.rememberInSession(session, type.getCode(), tags, excludes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", type.getCode());
        out.put("label", type.getLabel());
        out.put("emoji", type.getEmoji());
        out.put("tagline", type.getTagline());
        out.put("style", type.getStyle());
        out.put("tags", tags);
        out.put("excludes", excludes);
        out.put("saved", saved);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> readInts(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> raw)) {
            return null;
        }
        try {
            return raw.stream().map(v -> v instanceof Number n ? n.intValue() : null).toList();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readStrings(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}

package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.RegionBundle;
import com.sunz.hidden_travel.controller.dto.RegionMetric;
import com.sunz.hidden_travel.controller.dto.RegionSummary;
import com.sunz.hidden_travel.service.DummyRegionData;
import com.sunz.hidden_travel.service.RegionQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 화면 라우팅 컨트롤러.
 * 지역 상세/패널은 {@link RegionQueryService}(DB 실데이터)로 채운다.
 * 코스 편집/저장 등 아직 데이터가 없는 화면은 더미({@link DummyRegionData})를 유지한다.
 * 기본 지역은 안동시(SIG_CD 47170).
 */
@Controller
public class PageController {

    private static final String DEFAULT_SIG = "47170"; // 안동시

    /** 지도 왼쪽에 띄울 스포트라이트 카드 수 */

    private final DummyRegionData regionData;
    private final RegionQueryService regionQueryService;
    private final com.sunz.hidden_travel.user.CurrentUserService currentUserService;
    private final com.sunz.hidden_travel.service.RegionIntroService regionIntroService;

    /** 카카오맵 JavaScript 키 (출발지 역지오코딩용, 없으면 목적지만 길찾기) */
    @Value("${kakao.js.key:}")
    private String kakaoJsKey;

    public PageController(DummyRegionData regionData, RegionQueryService regionQueryService,
                          com.sunz.hidden_travel.user.CurrentUserService currentUserService,
                          com.sunz.hidden_travel.service.RegionIntroService regionIntroService) {
        this.regionData = regionData;
        this.regionQueryService = regionQueryService;
        this.currentUserService = currentUserService;
        this.regionIntroService = regionIntroService;
    }

    private RegionSummary toSummary(RegionBundle b) {
        return new RegionSummary(b.name(), b.province(), b.aiSummary(),
                b.specialties(), b.shops(), b.briefCourse());
    }

    /* =========================================================
       라우팅
       ========================================================= */

    /**
     * 로그인 화면 (헤더/푸터 숨김).
     * Spring Security 의 loginPage 이기도 하다 — 로그인 처리는 /login(POST) 필터가 담당.
     * 이미 로그인한 사용자는 지도로 보낸다.
     */
    @GetMapping("/")
    public String login() {
        return currentUserService.currentId() != null ? "redirect:/map" : "login";
    }

    /* 회원가입 화면은 AuthController 가 담당한다(가입 처리와 같은 곳에 두기 위해) */

    /* 온보딩(여행 MBTI 검사)은 OnboardingController 가 담당한다 */

    /** 메인 탐색(지도) — 헤더/푸터 프래그먼트 사용 */
    @GetMapping("/map")
    public String map(Model model) {
        model.addAttribute("regionOptions", regionData.options());
        // '오늘의 숨은 여행지'를 걷어냈다.
        // 선정 기준이 '수도권 시도코드 하드코딩 배제 + 관광지 10곳 이상'이라
        // 랭킹을 없앤 게 아니라 다른 규칙으로 바꾼 것이었다(제품원칙 2와 충돌).
        // 이 자리는 온보딩 뒤 '내 발견 카드'가 쓴다.
        return "map";
    }

    /** 지역 상세 탐색 — 지도 우측 슬라이드 패널(독립 페이지 버전, 실데이터) */
    @GetMapping("/region/panel")
    public String regionPanel(Model model) {
        RegionBundle b = regionQueryService.bundle(DEFAULT_SIG);
        model.addAttribute("sigCd", DEFAULT_SIG);
        model.addAttribute("region", toSummary(b));
        model.addAttribute("dayPlanAvailable", b.dayPlanAvailable());
        return "region-panel";
    }

    /** 지역 상세(깊이 있는 탐색) — 전체 페이지, 실데이터 */
    @GetMapping("/region")
    public String regionDetail(@RequestParam(value = "sigCd", required = false) String sigCd, Model model) {
        String cd = sigCd != null ? sigCd : DEFAULT_SIG;
        RegionBundle b = regionQueryService.bundle(cd);
        model.addAttribute("sigCd", cd);
        model.addAttribute("region", toSummary(b));
        model.addAttribute("destLat", b.lat());
        model.addAttribute("destLng", b.lng());
        model.addAttribute("kakaoJsKey", kakaoJsKey);
        model.addAttribute("heroDesc", b.aiSummary());
        // '이 지역에서 하루 보내기'를 내보낼 수 있는 지역인지
        model.addAttribute("dayPlanAvailable", b.dayPlanAvailable());
        model.addAttribute("metrics", List.of(
                new RegionMetric(String.valueOf(b.attractionCount()), "관광 콘텐츠 수"),
                new RegionMetric(String.valueOf(b.foodCount()), "맛집 수"),
                new RegionMetric(String.valueOf(b.shopCount()), "착한가격업소 수"),
                new RegionMetric(String.valueOf(b.specialtyCount()), "특산물 수")
        ));
        model.addAttribute("recommendedCourses", b.recommendedCourses());
        // "이 지역은 이런 곳이에요" — 적재된 실데이터로 구성 (TourAPI 에 지역 소개글이 없다)
        model.addAttribute("intro", regionIntroService.intro(cd));
        return "region-detail";
    }
}

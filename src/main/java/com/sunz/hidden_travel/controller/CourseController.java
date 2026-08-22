package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.CoursePageData;
import com.sunz.hidden_travel.controller.dto.DayPlan;
import com.sunz.hidden_travel.controller.dto.Recommendation;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.SavedCourse;
import com.sunz.hidden_travel.domain.SavedCourseStop;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.service.DayPlanService;
import com.sunz.hidden_travel.service.RegionQueryService;
import com.sunz.hidden_travel.service.SavedCourseService;
import com.sunz.hidden_travel.user.CurrentUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 내 코스 만들기 / 저장 완료 화면.
 * 후보 목록은 실데이터(Attraction/FoodPlace/GoodPriceShop/Specialty)를 sigCd로 조회해 채운다.
 * 코스 편집은 클라이언트 상태(course.js)로 관리하고, 저장 시 {@link SavedCourse} 로 영속화한다.
 */
@Controller
public class CourseController {

    private static final String DEFAULT_SIG = "47170"; // 안동시

    /** 카카오맵 JavaScript 키 (없으면 지도 자리에 설정 안내를 띄운다) */
    @org.springframework.beans.factory.annotation.Value("${kakao.js.key:}")
    private String kakaoJsKey;

    /** 하루 코스 '다른 조합' 회차 상한 — 무한정 올려도 의미가 없다 */
    private static final int MAX_VARIANT = 99;

    private final RegionQueryService regionQueryService;
    private final RegionRepository regionRepository;
    private final SavedCourseService savedCourseService;
    private final CurrentUserService currentUserService;
    private final DayPlanService dayPlanService;

    public CourseController(RegionQueryService regionQueryService,
                            RegionRepository regionRepository,
                            SavedCourseService savedCourseService,
                            CurrentUserService currentUserService,
                            DayPlanService dayPlanService) {
        this.regionQueryService = regionQueryService;
        this.regionRepository = regionRepository;
        this.savedCourseService = savedCourseService;
        this.currentUserService = currentUserService;
        this.dayPlanService = dayPlanService;
    }

    /**
     * 내 코스 만들기 — sigCd 후보 데이터 + (courseId) 초기 코스.
     *
     * @param auto    '이 지역에서 하루 보내기'로 들어온 경우. 오전·점심·오후·저녁을 자동 조립해
     *                타임라인에 미리 채운다. 사용자는 그 상태에서 바로 고칠 수 있다.
     * @param variant 같은 지역에서 다른 조합을 보고 싶을 때의 회차
     */
    @GetMapping("/course")
    public String course(@RequestParam(required = false) String sigCd,
                         @RequestParam(required = false) Long courseId,
                         @RequestParam(required = false, defaultValue = "false") boolean auto,
                         @RequestParam(required = false, defaultValue = "0") int variant,
                         Model model) {
        String cd = sigCd != null ? sigCd : DEFAULT_SIG;
        CoursePageData data = regionQueryService.coursePageData(cd, courseId);

        // 추천 코스를 담아온 경우(courseId)에는 그쪽이 우선 — 두 초기 코스가 겹치지 않게 한다
        if (auto && courseId == null) {
            DayPlan plan = dayPlanService.plan(cd, Math.clamp(variant, 0, MAX_VARIANT));
            model.addAttribute("dayPlan", plan);
            if (plan.available()) {
                data = data.withInitialCourse(plan.title(), DayPlanService.toInitialItems(plan));
            }
        }

        model.addAttribute("data", data);
        // 지도에 동선을 그리는 데 필요. 키가 없으면 화면이 안내를 띄운다.
        model.addAttribute("kakaoJsKey", kakaoJsKey);
        return "course";
    }

    /** 코스 저장 → DB 영속화 후 저장 완료 화면으로 */
    @PostMapping("/course/save")
    public String save(@RequestParam(required = false) String sigCd,
                       @RequestParam(required = false) String courseName,
                       @RequestParam(required = false) String itemsJson) {
        String cd = sigCd != null ? sigCd : DEFAULT_SIG;
        Long userId = currentUserService.currentId();
        SavedCourse saved = savedCourseService.save(userId, cd, courseName, itemsJson);
        if (saved == null) {
            // 경유지가 없거나 파싱 실패 → 편집 화면으로 되돌린다
            return "redirect:/course?sigCd=" + cd;
        }
        return "redirect:/course/saved?courseId=" + saved.getId();
    }

    /** 코스 저장 완료 — 저장된 코스를 DB 에서 읽어 렌더 */
    @GetMapping("/course/saved")
    public String saved(@RequestParam(required = false) Long courseId, Model model) {
        SavedCourse course = savedCourseService.find(courseId);
        // 없는 코스이거나 남의 코스면 목록으로 (id 만 바꿔 남의 코스를 열어보지 못하게)
        if (course == null || !course.getUserId().equals(currentUserService.currentId())) {
            return "redirect:/my/courses";
        }

        Region region = regionRepository.findById(course.getSigCd()).orElse(null);
        String regionLabel = region != null ? (region.getProvince() + " " + region.getName()) : "";

        model.addAttribute("course", course);
        model.addAttribute("stopNames", course.getStops().stream().map(SavedCourseStop::getName).toList());
        // 좌표가 있는 경유지만 지도에 그린다
        model.addAttribute("mapStops", course.getStops().stream().filter(SavedCourseStop::hasCoord).toList());
        model.addAttribute("regionLabel", regionLabel);
        model.addAttribute("kakaoJsKey", kakaoJsKey);
        model.addAttribute("recommendations", nextRecommendations());
        return "course-saved";
    }

    private List<Recommendation> nextRecommendations() {
        return List.of(
                new Recommendation("경북 의성군", "조용한 산사 산책", "사람 없는 고요한 사찰과 솔숲길"),
                new Recommendation("경북 영양군", "별빛 흐르는 밤", "국제밤하늘보호공원의 은하수"),
                new Recommendation("경북 봉화군", "오지 간이역 여행", "세월이 멈춘 산골 기차역")
        );
    }
}

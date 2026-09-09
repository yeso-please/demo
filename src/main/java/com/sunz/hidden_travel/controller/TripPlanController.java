package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.TripPlan;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.service.TravelPreferenceService;
import com.sunz.hidden_travel.service.TripPlanService;
import com.sunz.hidden_travel.user.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 여러 날 여행 계획 — 생성 · 상세 계획 화면 · 편집 API (PRD v4 F-06 ~ F-10).
 *
 * <p>흐름: 지도/지역에서 <b>이 지역으로 여행 떠나기</b> → {@code /trip/new} 가 초안을 만들고
 * {@code /trip/{id}} 상세 계획으로 보낸다. 편집은 화면에서 하고 저장은 명시적으로 누른다.
 *
 * <p>비회원도 초안을 만들고 고칠 수 있다(세션 소유). 로그인하면 그 초안이 계정으로 넘어온다.
 */
@Controller
public class TripPlanController {

    private static final String DEFAULT_SIG = "47170"; // 안동시

    @Value("${kakao.js.key:}")
    private String kakaoJsKey;

    private final TripPlanService tripPlanService;
    private final TravelPreferenceService preferenceService;
    private final RegionRepository regionRepository;
    private final CurrentUserService currentUserService;

    public TripPlanController(TripPlanService tripPlanService,
                              TravelPreferenceService preferenceService,
                              RegionRepository regionRepository,
                              CurrentUserService currentUserService) {
        this.tripPlanService = tripPlanService;
        this.preferenceService = preferenceService;
        this.regionRepository = regionRepository;
        this.currentUserService = currentUserService;
    }

    /* =========================================================
       화면
       ========================================================= */

    /**
     * '이 지역으로 여행 떠나기' — 초안을 만들고 상세 계획으로 보낸다.
     *
     * @param days  여행 일수 (1~7, 기본 1)
     * @param start 시작일 (yyyy-MM-dd, 선택). 날짜 없이 일수만으로도 계획할 수 있다
     * @param entry 랜덤 도착에서 왔는지(random) 직접 골랐는지(direct)
     */
    @GetMapping("/trip/new")
    public String create(@RequestParam(required = false) String sigCd,
                         @RequestParam(required = false, defaultValue = "1") int days,
                         @RequestParam(required = false) String start,
                         @RequestParam(required = false, defaultValue = "direct") String entry,
                         HttpServletRequest request) {
        String cd = sigCd != null && !sigCd.isBlank() ? sigCd : DEFAULT_SIG;
        HttpSession session = request.getSession(true);

        TripPlan plan = tripPlanService.create(
                cd, days, parseDate(start),
                "random".equalsIgnoreCase(entry) ? TripPlan.Entry.RANDOM : TripPlan.Entry.DIRECT,
                currentUserService.currentId(), session.getId(),
                preferenceService.snapshot(currentUserService.current(), session));

        return "redirect:/trip/" + plan.getId();
    }

    /** 상세 계획 화면 */
    @GetMapping("/trip/{id}")
    public String detail(@PathVariable Long id, HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(true);
        Long userId = currentUserService.currentId();

        // 로그인한 김에 이 세션의 비회원 초안을 계정으로 옮긴다(입력이 사라지지 않게)
        if (userId != null) {
            tripPlanService.claimGuestPlans(userId, session.getId());
        }

        TripPlan plan = tripPlanService.find(id);
        if (!tripPlanService.canAccess(plan, userId, session.getId())) {
            return "redirect:/my/trips";
        }

        Region region = regionRepository.findById(plan.getSigCd()).orElse(null);

        model.addAttribute("plan", plan);
        model.addAttribute("regionName", region != null ? region.getName() : "이 지역");
        model.addAttribute("province", region != null ? region.getProvince() : "");
        model.addAttribute("kakaoJsKey", kakaoJsKey);
        model.addAttribute("guestDraft", plan.getUserId() == null);
        model.addAttribute("preferenceSummary", preferenceSummary(plan));
        return "trip-plan";
    }

    /** 내 여행 목록 — 확정한 계획을 여행 당일 다시 여는 자리 */
    @GetMapping("/my/trips")
    public String myTrips(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(true);
        Long userId = currentUserService.currentId();
        if (userId != null) {
            tripPlanService.claimGuestPlans(userId, session.getId());
        }

        List<TripPlan> trips = tripPlanService.myTrips(userId, session.getId());
        Map<Long, String> regionLabels = new LinkedHashMap<>();
        trips.forEach(t -> regionRepository.findById(t.getSigCd())
                .ifPresent(r -> regionLabels.put(t.getId(), r.getProvince() + " " + r.getName())));

        model.addAttribute("trips", trips);
        model.addAttribute("regionLabels", regionLabels);
        model.addAttribute("loggedIn", userId != null);
        return "my-trips";
    }

    /* =========================================================
       편집 API
       ========================================================= */

    /** 일정 저장 — 버전이 어긋나면 저장하지 않고 409 로 알린다(편집 내용은 화면이 그대로 들고 있다) */
    @PostMapping("/api/trip/{id}/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long id,
                                                    @RequestBody SaveRequest body,
                                                    HttpServletRequest request) {
        TripPlan plan = authorized(id, request);
        if (plan == null) {
            return denied();
        }
        TripPlanService.SaveResult result = tripPlanService.saveItems(plan, body.version(), body.days());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("version", result.version());
        if (!result.ok()) {
            out.put("message", result.message());
            out.put("conflict", result.conflict());
            return ResponseEntity.status(result.conflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST).body(out);
        }
        return ResponseEntity.ok(out);
    }

    /** 그 Day 동선 주변 먹거리 후보 — '주변 먹거리 찾기' */
    @GetMapping("/api/trip/{id}/food-candidates")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> foodCandidates(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "1") int day,
                                                              HttpServletRequest request) {
        TripPlan plan = authorized(id, request);
        if (plan == null) {
            return denied();
        }
        var tripDay = plan.day(day);
        var base = tripDay == null ? null : tripDay.lastPlace();

        List<TripPlanService.FoodCandidate> candidates = tripPlanService.foodCandidates(
                plan.getSigCd(),
                base != null ? base.getLat() : null,
                base != null ? base.getLng() : null,
                20);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseName", base != null ? base.getName() : null);
        out.put("candidates", candidates.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("category", c.category());
            m.put("addr", c.addr());
            m.put("sage", c.sage());
            m.put("priceText", c.priceText());
            m.put("lat", c.lat());
            m.put("lng", c.lng());
            m.put("distanceText", c.distanceText());
            return m;
        }).toList());
        return ResponseEntity.ok(out);
    }

    /** 고른 먹거리를 그 Day 에 등록 */
    @PostMapping("/api/trip/{id}/food")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setFood(@PathVariable Long id,
                                                       @RequestBody FoodRequest body,
                                                       HttpServletRequest request) {
        TripPlan plan = authorized(id, request);
        if (plan == null) {
            return denied();
        }
        boolean ok = tripPlanService.setFood(plan, body.dayIndex(), body.name(), body.addr(),
                body.sage(), body.priceText(), body.lat(), body.lng());
        return ok ? ResponseEntity.ok(Map.of("ok", true, "version", plan.getVersion()))
                  : ResponseEntity.badRequest().body(Map.of("ok", false, "message", "그 Day 를 찾을 수 없어요."));
    }

    /** 고른 숙소를 그 Day 밤에 등록. 예약 여부는 우리가 확인하지 않는다 */
    @PostMapping("/api/trip/{id}/stay")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setStay(@PathVariable Long id,
                                                       @RequestBody StayRequest body,
                                                       HttpServletRequest request) {
        TripPlan plan = authorized(id, request);
        if (plan == null) {
            return denied();
        }
        boolean ok = tripPlanService.setStay(plan, body.dayIndex(), body.name(), body.addr(),
                body.url(), body.lat(), body.lng());
        return ok ? ResponseEntity.ok(Map.of("ok", true, "version", plan.getVersion()))
                  : ResponseEntity.badRequest().body(Map.of("ok", false, "message", "그 Day 를 찾을 수 없어요."));
    }

    /** 계획 확정 — 모든 Day 에 관광지가 하나 이상 있어야 한다 */
    @PostMapping("/api/trip/{id}/confirm")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Long id, HttpServletRequest request) {
        TripPlan plan = authorized(id, request);
        if (plan == null) {
            return denied();
        }
        if (!tripPlanService.confirm(plan)) {
            List<Integer> empty = plan.emptyDayIndexes();
            String where = empty.stream().map(d -> "Day " + d).collect(java.util.stream.Collectors.joining(", "));
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "emptyDays", empty,
                    "message", where + "에 장소가 없어요. 한 곳 이상 담아야 확정할 수 있어요."));
        }
        return ResponseEntity.ok(Map.of("ok", true, "status", plan.getStatus().name(), "version", plan.getVersion()));
    }

    /** 여행 이름 바꾸기 */
    @PostMapping("/api/trip/{id}/title")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> rename(@PathVariable Long id,
                                                      @RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        TripPlan plan = authorized(id, request);
        if (plan == null) {
            return denied();
        }
        tripPlanService.rename(plan, body.get("title"));
        return ResponseEntity.ok(Map.of("ok", true, "title", plan.getTitle(), "version", plan.getVersion()));
    }

    /* =========================================================
       내부
       ========================================================= */

    /** 권한을 확인하고 계획을 돌려준다. 남의 계획이면 null — 존재 여부도 알려주지 않는다 */
    private TripPlan authorized(Long id, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String guestKey = session != null ? session.getId() : null;
        TripPlan plan = tripPlanService.find(id);
        return tripPlanService.canAccess(plan, currentUserService.currentId(), guestKey) ? plan : null;
    }

    private ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("ok", false, "message", "이 여행에 접근할 수 없어요."));
    }

    private String preferenceSummary(TripPlan plan) {
        if (!plan.isPreferenceAnswered()) {
            return "성향을 아직 입력하지 않아 균형형으로 만들었어요";
        }
        StringBuilder sb = new StringBuilder();
        if (plan.getMbtiCode() != null) {
            sb.append(plan.getMbtiCode());
        }
        if (plan.getPreferenceTags() != null && !plan.getPreferenceTags().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(plan.getPreferenceTags().replace(",", " · "));
        }
        return sb.isEmpty() ? "성향을 아직 입력하지 않아 균형형으로 만들었어요" : sb.toString();
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;   // 날짜는 선택 사항이라 잘못 들어와도 계획 생성을 막지 않는다
        }
    }

    /* ----- 요청 본문 ----- */

    public record SaveRequest(int version, List<TripPlanService.DayPayload> days) {}

    public record FoodRequest(int dayIndex, String name, String addr, boolean sage,
                              String priceText, Double lat, Double lng) {}

    public record StayRequest(int dayIndex, String name, String addr, String url, Double lat, Double lng) {}
}

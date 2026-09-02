package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.DayPlan;
import com.sunz.hidden_travel.controller.dto.DiscoveryCandidate;
import com.sunz.hidden_travel.controller.dto.TravelerDna;
import com.sunz.hidden_travel.controller.dto.VisitInput;
import com.sunz.hidden_travel.domain.SavedCourse;
import com.sunz.hidden_travel.repository.SavedCourseRepository;
import com.sunz.hidden_travel.service.DayPlanService;
import com.sunz.hidden_travel.service.DiaryService;
import com.sunz.hidden_travel.service.ExperienceTags;
import com.sunz.hidden_travel.service.TravelerProfileService;
import com.sunz.hidden_travel.user.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 방문 이력 온보딩 → 여행 DNA → 발견.
 *
 * <p>여행 MBTI(12문항)를 대체하는 입력이다. 추상적인 설문 대신
 * <b>실제로 좋았던 지역</b>을 고르게 해서 취향을 읽는다.
 *
 * <p><b>로그인을 요구하지 않는다.</b> 첫 가치 경험을 로그인 뒤로 미루면 온보딩이 그대로 이탈한다.
 * 입력은 세션에 두고, 가입하면 그때 사용자에게 옮기면 된다.
 */
@Controller
public class VisitOnboardingController {

    /** 세션 키 — 로그인 전 입력 보관 */
    static final String SESSION_VISITS = "onboarding.visits";

    /** [다녀왔어요]로 들어온 코스 — 이 세션에서 쓰는 편에 붙는다 */
    static final String SESSION_COURSE = "onboarding.courseId";

    /**
     * 한 편만 써도 결과를 본다.
     *
     * <p>예전에는 3편을 요구했는데 그 숫자에 근거가 없었다 — 실측하니 3→4→5→6 에서
     * 후보가 계속 절반씩 바뀌어 "3편이면 충분하다"가 성립하지 않았다.
     * 몇 편이 필요한지는 사용자가 정하고, 우리는 <b>지금 몇 편으로 읽은 결과인지</b>를 밝힌다.
     */
    private static final int MIN_VISITS = 1;

    /** 상한은 규칙이 아니라 방어선이다 — 세션에 담기는 양을 제한할 뿐 권장값이 아니다. */
    private static final int MAX_VISITS = 30;
    private static final int DISCOVER_LIMIT = 3;
    /** 전국 시군구 수 — sig.json 기준. '250곳 중 N곳' 표시에 쓴다. */
    private static final int TOTAL_REGIONS = 250;

    private final TravelerProfileService profileService;
    private final DayPlanService dayPlanService;
    private final CurrentUserService currentUserService;
    private final DiaryService diaryService;
    private final SavedCourseRepository savedCourseRepository;

    public VisitOnboardingController(TravelerProfileService profileService,
                                     DayPlanService dayPlanService,
                                     CurrentUserService currentUserService,
                                     DiaryService diaryService,
                                     SavedCourseRepository savedCourseRepository) {
        this.profileService = profileService;
        this.dayPlanService = dayPlanService;
        this.currentUserService = currentUserService;
        this.diaryService = diaryService;
        this.savedCourseRepository = savedCourseRepository;
    }

    /* =========================================================
       화면
       ========================================================= */

    /**
     * 여행 다이어리.
     *
     * <p><b>기억과 기록이 같은 화면이다.</b> 그냥 들어오면 과거 여행을 적는 '기억'이고,
     * 저장한 코스에서 [다녀왔어요]로 들어오면({@code courseId}) 그 코스로 실제 다녀온 '기록'이다.
     * 기록으로 들어오면 지역이 이미 정해져 있으니 지역 고르기를 건너뛰고 바로 쓰기로 연다.
     *
     * <p>기록에는 코스가 붙는다({@code Diary.savedCourseId}) — <b>추천이 맞았는지 알 수 있는
     * 유일한 증거</b>라서, 이게 붙어야 '추천 → 다녀옴 → 다시 적음' 루프가 닫힌다.
     */
    @GetMapping("/onboarding/visits")
    public String visits(@RequestParam(required = false) Long courseId,
                         Model model, HttpSession session) {
        model.addAttribute("tags", ExperienceTags.all());
        model.addAttribute("minVisits", MIN_VISITS);
        model.addAttribute("maxVisits", MAX_VISITS);
        model.addAttribute("loggedIn", currentUserService.currentId() != null);
        model.addAttribute("saved", stored(session).size());

        SavedCourse course = courseFor(courseId);
        if (course != null) {
            // 저장 시점에 이 코스를 붙일 수 있게 세션에 들고 있는다.
            // (URL 파라미터를 그대로 믿지 않는다 — 아래 courseFor 가 소유자를 확인한다)
            session.setAttribute(SESSION_COURSE, course.getId());
            model.addAttribute("courseId", course.getId());
            model.addAttribute("courseSigCd", course.getSigCd());
            model.addAttribute("courseTitle", course.getTitle());
        } else {
            session.removeAttribute(SESSION_COURSE);
        }
        return "onboarding-visits";
    }

    /** 내 코스가 맞을 때만 돌려준다 — 남의 코스를 내 다이어리에 붙일 수 없어야 한다 */
    private SavedCourse courseFor(Long courseId) {
        Long userId = currentUserService.currentId();
        if (courseId == null || userId == null) return null;
        return savedCourseRepository.findById(courseId)
                .filter(c -> userId.equals(c.getUserId()))
                .orElse(null);
    }

    /**
     * 옛 '여행 DNA 결과' 화면. 이제 카드와 지도가 곧 결과라 중간 화면을 두지 않는다
     * (PRD 5.1 — 화면을 넘겨 결과를 보여주면 "내가 고른 여행이 이 지역들을 불러냈다"는 연결이 끊긴다).
     * 링크가 남아 있을 수 있으니 지도로 보낸다.
     */
    @GetMapping("/onboarding/dna")
    public String dna() {
        return "redirect:/map";
    }

    /* =========================================================
       API
       ========================================================= */

    /** 입력 저장 (세션). 화면은 부분 저장도 계속 보내므로 매번 통째로 덮어쓴다. */
    @PostMapping("/api/onboarding/visits")
    @ResponseBody
    public Map<String, Object> saveVisits(@RequestBody List<VisitInput> body, HttpSession session) {
        List<VisitInput> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (body != null) {
            for (VisitInput v : body) {
                if (v == null || v.sigCd() == null || v.sigCd().isBlank()) continue;
                if (!diaryService.knownRegion(v.sigCd())) continue;   // 없는 코드는 지도에 못 붙는다
                if (!seen.add(v.sigCd())) continue;          // 같은 지역 중복 방지
                if (clean.size() >= MAX_VISITS) break;
                clean.add(v);
            }
        }
        Long userId = currentUserService.currentId();
        if (userId != null) {
            // [다녀왔어요]로 들어왔다면 그 코스의 지역에 쓴 편에만 코스를 붙인다.
            // 같은 화면에서 다른 지역을 더 적을 수도 있는데, 그건 '기억'이지 이 코스의 기록이 아니다.
            SavedCourse course = courseFor((Long) session.getAttribute(SESSION_COURSE));
            for (VisitInput v : clean) {
                boolean isRecord = course != null && course.getSigCd().equals(v.sigCd());
                diaryService.save(userId, v, isRecord ? course.getId() : null);
            }
            session.removeAttribute(SESSION_VISITS);
        } else {
            session.setAttribute(SESSION_VISITS, clean);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", clean.size());
        out.put("enough", clean.size() >= MIN_VISITS);
        out.put("remaining", Math.max(0, MIN_VISITS - clean.size()));
        return out;
    }

    /**
     * 쓴 글에서 경험을 읽어 <b>어디에 적혀 있는지</b>까지 돌려준다.
     *
     * <p>다이어리 화면이 본문에 밑줄을 긋는 데 쓴다. 태그만 주면 "왜 이게 나왔는지"를
     * 보여줄 수 없어서, 위치를 함께 준다.
     */
    @PostMapping("/api/onboarding/read")
    @ResponseBody
    public Map<String, Object> readNote(@RequestBody Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        List<ExperienceTags.Span> spans = ExperienceTags.spans(note);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tags", ExperienceTags.tagsFrom(note));
        out.put("spans", spans.stream().map(sp -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", sp.start());
            m.put("end", sp.end());
            m.put("tag", sp.tag());
            m.put("word", sp.word());
            return m;
        }).toList());
        return out;
    }

    /** 저장된 입력 조회 — 새로고침해도 이어서 하게 */
    @GetMapping("/api/onboarding/visits")
    @ResponseBody
    public List<VisitInput> loadVisits(HttpSession session) {
        return stored(session);
    }

    /**
     * 여행 DNA + 발견 후보.
     *
     * 후보는 점수 없이 <b>동등한 카드</b>로 내려간다 — 순위를 매기지 않는 것이 이 서비스의 전제다.
     */
    @GetMapping("/api/onboarding/dna")
    @ResponseBody
    public Map<String, Object> dnaResult(@RequestParam(defaultValue = "3") int limit, HttpSession session) {
        List<VisitInput> visits = stored(session);
        Map<String, Object> out = new LinkedHashMap<>();

        if (visits.size() < MIN_VISITS) {
            out.put("enough", false);
            out.put("remaining", MIN_VISITS - visits.size());
            return out;
        }

        TravelerDna dna = profileService.buildDna(visits);
        Set<String> visited = new LinkedHashSet<>();
        for (VisitInput v : visits) visited.add(v.sigCd());

        int n = Math.max(1, Math.min(limit, 12));
        List<DiscoveryCandidate> candidates = profileService.discover(dna, visited, n);

        out.put("enough", true);
        out.put("sentence", dna.sentence());
        out.put("axes", dna.axes());
        out.put("basis", dna.basis());
        out.put("candidateCount", profileService.candidateCount(dna, visited));
        out.put("candidates", candidates);
        out.put("visitedCount", visits.size());
        return out;
    }

    /**
     * 내 발견 지도 — 250곳 중 내가 밝힌 곳 (PRD 5.3 ③).
     *
     * <p>여행 서비스는 저빈도라 한 번 쓰고 끝나기 쉽다. 어두운 전국 지도가 내 여행으로
     * 하나씩 켜지게 해서 <b>돌아올 이유</b>를 만든다.
     *
     * <p><b>시각 문법 주의</b> — {@code /map} 에서 켜진 불은 '추천 후보'이고
     * 여기서 켜진 불은 '다녀온 곳'이다. 한 화면에서 두 의미를 섞지 않는다.
     */
    @GetMapping("/my/discoveries")
    public String myDiscoveries(Model model, HttpSession session) {
        List<VisitInput> visits = stored(session);
        model.addAttribute("litCount", visits.size());
        model.addAttribute("totalRegions", TOTAL_REGIONS);
        model.addAttribute("loggedIn", currentUserService.currentId() != null);

        // 공유 링크는 '공개한 편이 있는 로그인 사용자'에게만 준다.
        // 비로그인 사용자의 다이어리는 세션에만 있어 남이 열 수 있는 주소가 없고,
        // 공개한 편이 없으면 링크를 눌러도 빈 지도가 나온다.
        var me = currentUserService.current();
        if (me != null) {
            var mine = diaryService.publicMap(me.getNickname(), TOTAL_REGIONS);
            if (mine != null && !mine.isEmpty()) {
                // 경로만 넘긴다 — 붙여넣을 수 있는 전체 주소는 화면이 현재 호스트로 만든다
                model.addAttribute("shareUrl", "/u/" + me.getNickname());
            }
        }
        return "my-discoveries";
    }

    /**
     * 근교 나들이 — "이번 주말 어디 가지" (PRD 5.3 ①).
     *
     * <p>여행은 연 몇 회지만 나들이는 매주다. <b>빈도를 실제로 올리는 유일한 축</b>이라
     * 하루 코스와 별도 화면으로 둔다.
     */
    @GetMapping("/nearby")
    public String nearby(Model model, HttpSession session) {
        model.addAttribute("hasProfile", stored(session).size() >= MIN_VISITS);
        return "nearby";
    }

    /**
     * 위치 기준 근교 후보 + 반나절 코스.
     *
     * @param km 직선거리 상한. 실제 이동시간이 아니므로 화면에서 '대략'임을 밝힌다.
     */
    @GetMapping("/api/nearby")
    @ResponseBody
    public Map<String, Object> nearbyData(@RequestParam double lat,
                                          @RequestParam double lng,
                                          @RequestParam(defaultValue = "40") double km,
                                          @RequestParam(defaultValue = "4") int limit,
                                          HttpSession session) {
        List<VisitInput> visits = stored(session);
        // 취향이 없으면(온보딩 전) 가까운 순으로 준다 — 나들이는 로그인도 온보딩도 막지 않는다
        TravelerDna dna = visits.size() >= MIN_VISITS ? profileService.buildDna(visits) : null;
        Set<String> visited = new LinkedHashSet<>();
        for (VisitInput v : visits) visited.add(v.sigCd());

        List<DiscoveryCandidate> regions = profileService.discoverNearby(
                dna, visited, lat, lng, Math.max(5, Math.min(km, 150)), Math.max(1, Math.min(limit, 8)));

        List<Map<String, Object>> out = new ArrayList<>();
        for (DiscoveryCandidate c : regions) {
            DayPlan plan = dayPlanService.plan(c.sigCd(), 0, DayPlanService.Length.HALF);
            if (!plan.available()) continue;      // 코스가 안 나오면 추천하지 않는다(제품원칙 6)
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("region", c);
            m.put("plan", plan);
            out.add(m);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("hasProfile", dna != null);
        res.put("km", km);
        res.put("items", out);
        return res;
    }

    /** 내가 밝힌 지역 목록 (지도 렌더용) */
    @GetMapping("/api/my/discoveries")
    @ResponseBody
    public Map<String, Object> myDiscoveryData(HttpSession session) {
        // 지도에 못 붙는 지역은 DiaryService.asVisits 에서 이미 걸러져 온다
        List<VisitInput> visits = stored(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", TOTAL_REGIONS);
        out.put("lit", visits.size());
        out.put("items", visits.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sigCd", v.sigCd());
            m.put("satisfaction", v.satisfaction());
            m.put("tags", v.tags());
            return m;
        }).toList());
        return out;
    }

    /**
     * 지도에 얹을 발견 후보 — 지도는 카드보다 많이 보여주므로 기본 12곳.
     *
     * <p>순서가 곧 순위로 읽히지 않도록 <b>지역명 가나다순으로 정렬해서</b> 내려보낸다.
     * 지도에서도 모든 후보를 같은 색·같은 굵기로 칠한다.
     */
    @GetMapping("/api/onboarding/candidates")
    @ResponseBody
    public Map<String, Object> candidates(@RequestParam(defaultValue = "12") int limit, HttpSession session) {
        List<VisitInput> visits = stored(session);
        Map<String, Object> out = new LinkedHashMap<>();
        if (visits.size() < MIN_VISITS) {
            out.put("enough", false);
            return out;
        }
        TravelerDna dna = profileService.buildDna(visits);
        Set<String> visited = new LinkedHashSet<>();
        for (VisitInput v : visits) visited.add(v.sigCd());

        List<DiscoveryCandidate> list = new ArrayList<>(
                profileService.discover(dna, visited, Math.max(1, Math.min(limit, 30))));
        list.sort(java.util.Comparator.comparing(
                c -> c.courseTitle() != null ? c.courseTitle() : c.name()));

        out.put("enough", true);
        out.put("sentence", dna.sentence());
        out.put("total", profileService.candidateCount(dna, visited));
        out.put("visited", visited);
        out.put("items", list);
        return out;
    }

    /* =========================================================
       내부
       ========================================================= */

    /**
     * 지금 사용자의 다이어리.
     *
     * <p>로그인했으면 DB, 아니면 세션이다. 세션만 쓰던 시절에는 브라우저를 닫으면
     * 전부 사라졌는데, 재방문 흐름(지도가 쌓인다)이 통째로 그 위에 서 있었다.
     */
    private List<VisitInput> stored(HttpSession session) {
        Long userId = currentUserService.currentId();
        if (userId != null) {
            // 로그인 전에 쓴 편이 세션에 남아 있으면 이 시점에 사용자에게 옮긴다
            List<VisitInput> pending = sessionVisits(session);
            if (!pending.isEmpty()) {
                diaryService.adopt(userId, pending);
                session.removeAttribute(SESSION_VISITS);
            }
            return diaryService.asVisits(userId);
        }
        return sessionVisits(session);
    }

    @SuppressWarnings("unchecked")
    private List<VisitInput> sessionVisits(HttpSession session) {
        Object v = session.getAttribute(SESSION_VISITS);
        return v instanceof List<?> list ? (List<VisitInput>) list : List.of();
    }
}

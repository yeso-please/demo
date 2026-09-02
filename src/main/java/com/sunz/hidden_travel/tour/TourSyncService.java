package com.sunz.hidden_travel.tour;

import com.sunz.hidden_travel.domain.Attraction;
import com.sunz.hidden_travel.domain.CoursePoint;
import com.sunz.hidden_travel.domain.FoodPlace;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.TravelCourse;
import com.sunz.hidden_travel.geo.SigGeometryService;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.repository.FoodPlaceRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.TravelCourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TourAPI 적재 배치. 웹 요청과 분리되어 관리자 엔드포인트로만 호출된다.
 *
 * 좌표 기반 SIG_CD 판정:
 *  - 단일 지역(syncRegion): Region 중심좌표 + 반경으로 locationBasedList 호출 후,
 *    각 항목을 그 지역 폴리곤과 point-in-polygon 으로 확인해 적재(인접 지역 항목 배제).
 *  - 시도(syncSido): areaBasedList 로 시도 전체를 받아 각 항목 좌표로 SIG_CD 판정.
 * 중복은 sourceContentId(=TourAPI contentId) 기준으로 방지한다.
 */
@Service
public class TourSyncService {

    private static final Logger log = LoggerFactory.getLogger(TourSyncService.class);

    private static final int CT_ATTRACTION = 12;
    private static final int CT_CULTURE = 14;      // 문화시설 — 박물관·미술관·전시관
    private static final int CT_FESTIVAL = 15;     // 축제공연행사 — searchFestival 로 따로 받는다
    private static final int CT_COURSE = 25;
    private static final int CT_LEISURE = 28;      // 레포츠
    private static final int CT_LODGING = 32;      // 숙박
    private static final int CT_SHOPPING = 38;     // 쇼핑
    private static final int CT_FOOD = 39;

    /**
     * areaBasedList 로 받는 유형. <b>순서가 곧 우선순위</b>다.
     * 하루 호출 한도(1,000회)가 중간에 소진돼도 앞쪽 유형이 최대한 채워진 상태로 끝난다.
     *
     * 축제(15)는 행사 기간이 필요해 searchFestival 로 따로 받는다 — {@link #syncFestivals(int)}.
     */
    private static final int[] CONTENT_TYPES = {
            CT_ATTRACTION, CT_FOOD, CT_CULTURE, CT_LODGING, CT_LEISURE, CT_SHOPPING, CT_COURSE
    };

    /** Attraction 테이블에 저장하는 유형 → type 컬럼 값 */
    private static final Map<Integer, String> ATTRACTION_TYPE_NAMES = Map.of(
            CT_ATTRACTION, "관광지",
            CT_CULTURE, "문화시설",
            CT_LEISURE, "레포츠",
            CT_LODGING, "숙박",
            CT_SHOPPING, "쇼핑",
            CT_FESTIVAL, "축제"
    );

    private static final int NUM_OF_ROWS = 100;
    private static final int MAX_PAGES = 30;          // 지역/시도당 안전 상한
    private static final int SINGLE_RADIUS = 20000;   // locationBased 최대 반경(m)
    private static final long CALL_DELAY_MS = 150;    // rate limit 회피

    /** 여행코스 경유지(코스당 호출 1회)를 채울 최소 잔여 예산 — 목록 적재를 우선한다 */
    private static final int COURSE_DETAIL_MIN_REMAINING = 150;

    /** 시도 코드(SIG_CD 앞 2자리) → TourAPI areaCode */
    private static final Map<String, Integer> SIDO_TO_AREA = Map.ofEntries(
            Map.entry("11", 1), Map.entry("28", 2), Map.entry("30", 3), Map.entry("27", 4),
            Map.entry("29", 5), Map.entry("26", 6), Map.entry("31", 7), Map.entry("36", 8),
            Map.entry("41", 31), Map.entry("42", 32), Map.entry("43", 33), Map.entry("44", 34),
            Map.entry("47", 35), Map.entry("48", 36), Map.entry("45", 37), Map.entry("46", 38),
            Map.entry("50", 39)
    );

    private final TourApiClient client;
    private final SigGeometryService geometry;
    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final FoodPlaceRepository foodPlaceRepository;
    private final TravelCourseRepository travelCourseRepository;

    public TourSyncService(TourApiClient client, SigGeometryService geometry,
                           RegionRepository regionRepository,
                           AttractionRepository attractionRepository,
                           FoodPlaceRepository foodPlaceRepository,
                           TravelCourseRepository travelCourseRepository) {
        this.client = client;
        this.geometry = geometry;
        this.regionRepository = regionRepository;
        this.attractionRepository = attractionRepository;
        this.foodPlaceRepository = foodPlaceRepository;
        this.travelCourseRepository = travelCourseRepository;
    }

    /* =========================================================
       단일 지역 (검증용) — 좌표 반경 + point-in-polygon
       ========================================================= */
    public Map<String, Object> syncRegion(String sigCd) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        if (region == null || region.getLat() == null || region.getLng() == null) {
            return Map.of("error", "region 없음 또는 좌표 없음: " + sigCd);
        }
        Counter c = new Counter();
        for (int type : CONTENT_TYPES) {
            for (int page = 1; page <= MAX_PAGES; page++) {
                if (client.remainingCalls() <= 0) { c.stoppedByBudget = true; break; }
                TourApiClient.TourPage p = client.locationBasedList(
                        region.getLng(), region.getLat(), SINGLE_RADIUS, type, page, NUM_OF_ROWS);
                if (p.items().isEmpty()) break;
                for (JsonNode item : p.items()) {
                    double[] xy = coord(item);
                    if (xy == null) { c.unmapped++; continue; }
                    // 반경 안이어도 실제로 이 지역 폴리곤에 속하는지 확인
                    if (!geometry.isInSigCd(sigCd, xy[0], xy[1])) { c.outside++; continue; }
                    upsert(type, item, sigCd, xy, c);
                }
                if (p.items().size() < NUM_OF_ROWS) break;
                sleep();
            }
        }
        Map<String, Object> result = summary(sigCd, c);
        log.info("[TourSync] syncRegion {} → {}", sigCd, result);
        return result;
    }

    /* =========================================================
       시도 단위 (전체 배치) — areaBasedList + 좌표 판정
       ========================================================= */
    public Map<String, Object> syncSido(int areaCode) {
        Counter c = new Counter();
        for (int type : CONTENT_TYPES) {
            syncSidoType(areaCode, type, c);
        }
        Map<String, Object> result = summary("area:" + areaCode, c);
        log.info("[TourSync] syncSido {} → {}", areaCode, result);
        return result;
    }

    /** 시도 1곳의 콘텐츠 유형 1종을 적재한다. 호출 한도가 남지 않으면 즉시 중단. */
    private void syncSidoType(int areaCode, int type, Counter c) {
        String sidoPrefix = sidoPrefixOf(areaCode);
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (client.remainingCalls() <= 0) {
                c.stoppedByBudget = true;
                return;
            }
            TourApiClient.TourPage p = client.areaBasedList(areaCode, type, page, NUM_OF_ROWS);
            if (p.items().isEmpty()) return;
            for (JsonNode item : p.items()) {
                double[] xy = coord(item);
                if (xy == null) { c.unmapped++; continue; }
                String sigCd = geometry.resolveSigCd(xy[0], xy[1], sidoPrefix)
                        .orElseGet(() -> geometry.resolveSigCd(xy[0], xy[1]).orElse(null));
                if (sigCd == null) {
                    c.outside++;
                    log.debug("[TourSync] 좌표→SIG_CD 실패: {} ({},{})", title(item), xy[0], xy[1]);
                    continue;
                }
                upsert(type, item, sigCd, xy, c);
            }
            if (p.items().size() < NUM_OF_ROWS) return;
            sleep();
        }
    }

    /* =========================================================
       축제 — searchFestival (행사 기간이 필요해 별도 오퍼레이션)
       ========================================================= */

    /**
     * 전국 축제·공연·행사를 적재한다.
     *
     * 시도 단위로 나눠 받지 않는다 — searchFestival 은 areaCode 필터가 동작하지 않는다
     * ({@link TourApiClient#searchFestival} 주석 참고). 전국을 한 번에 받고 좌표로 시군구를 판정하며,
     * 그래서 호출 수도 시도 순회보다 훨씬 적다(전국 수백 건 = 몇 회).
     *
     * @param eventStartDate yyyyMMdd. 이 날짜 이후 시작하거나 진행 중인 행사만 받는다.
     *                       비어 있으면 오늘 — 즉 <b>이미 끝난 축제는 받지 않는다.</b>
     */
    public Map<String, Object> syncFestivals(String eventStartDate) {
        String from = (eventStartDate == null || eventStartDate.isBlank())
                ? java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                : eventStartDate;
        Counter c = new Counter();

        for (int page = 1; page <= MAX_PAGES; page++) {
            if (client.remainingCalls() <= 0) { c.stoppedByBudget = true; break; }
            TourApiClient.TourPage p = client.searchFestival(from, page, NUM_OF_ROWS);
            if (p.items().isEmpty()) break;
            for (JsonNode item : p.items()) {
                double[] xy = coord(item);
                if (xy == null) { c.unmapped++; continue; }
                String sigCd = geometry.resolveSigCd(xy[0], xy[1]).orElse(null);
                if (sigCd == null) { c.outside++; continue; }
                upsert(CT_FESTIVAL, item, sigCd, xy, c);
            }
            if (p.items().size() < NUM_OF_ROWS) break;
            sleep();
        }

        Map<String, Object> result = summary("festival:from:" + from, c);
        log.info("[TourSync] syncFestivals (from {}) → {}", from, result);
        return result;
    }

    /** 전국 배치 (17개 시도 순회) */
    public Map<String, Object> syncAll() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (Integer areaCode : new java.util.TreeSet<>(SIDO_TO_AREA.values())) {
            all.put("area:" + areaCode, syncSido(areaCode));
        }
        return all;
    }

    /* =========================================================
       비어 있는 지역 채우기 (호출 한도 안에서)
       ========================================================= */

    /**
     * 관광지가 하나도 없는 시군구를 가진 시도만 골라 적재한다.
     *
     * 호출 예산이 하루 1000회로 제한되므로 유형 우선순위를 두어
     * <b>관광지를 모든 시도에 먼저 채운 뒤</b> 음식점 → 여행코스 순으로 넓힌다.
     * (한도가 중간에 소진돼도 "관광지가 빈 지역"이 최대한 줄어든 상태로 끝난다)
     */
    public Map<String, Object> syncMissing() {
        List<String> emptySidos = regionRepository.findSidoPrefixesWithoutAttraction();
        List<Integer> areas = emptySidos.stream()
                .map(SIDO_TO_AREA::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("대상 시도", areas);
        report.put("시작 시점 남은 호출", client.remainingCalls());

        Counter total = new Counter();
        boolean budgetOut = false;
        for (int type : CONTENT_TYPES) {          // 관광지 → 음식점 → 여행코스 순
            for (int areaCode : areas) {
                if (client.remainingCalls() <= 0) { budgetOut = true; break; }
                syncSidoType(areaCode, type, total);
            }
            if (budgetOut) break;
        }

        report.put("적재 결과", summary("missing", total));
        report.put("사용한 호출", client.usedCalls());
        report.put("남은 호출", client.remainingCalls());
        report.put("한도 소진으로 중단", budgetOut || total.stoppedByBudget);
        log.info("[TourSync] syncMissing → {}", report);
        return report;
    }

    /**
     * 이미 적재된 여행코스의 경유지를 다시 읽어 contentId·이미지를 채운다.
     * (이 필드들이 추가되기 전에 적재된 코스 보정용 — 코스 1건당 호출 1회)
     */
    public Map<String, Object> refreshCoursePoints() {
        List<TravelCourse> courses = travelCourseRepository.findAll();
        int updated = 0;
        int skipped = 0;
        boolean budgetOut = false;

        for (TravelCourse tc : courses) {
            // 경유지가 비어 있거나(=아직 못 받아온 코스) contentId 가 없는 경유지가 있으면 다시 받는다.
            // 비어 있는 경우를 제외하면, 한 번 비워진 코스가 영영 복구되지 않는다.
            boolean needs = tc.getPoints().isEmpty() || tc.getPoints().stream()
                    .anyMatch(p -> p.getContentId() == null || p.getContentId().isBlank());
            if (!needs) {
                skipped++;
                continue;
            }
            if (client.remainingCalls() <= 0) {
                budgetOut = true;
                break;
            }
            fillCoursePoints(tc, tc.getSourceContentId());
            travelCourseRepository.save(tc);
            updated++;
            sleep();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("전체 코스", courses.size());
        m.put("보정함", updated);
        m.put("이미 완료", skipped);
        m.put("남은 호출", client.remainingCalls());
        m.put("한도 소진으로 중단", budgetOut);
        log.info("[TourSync] refreshCoursePoints → {}", m);
        return m;
    }

    /** 현재 호출 예산 상태만 조회(호출 없음) */
    public Map<String, Object> budget() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("1일 한도", client.dailyLimit());
        m.put("오늘 사용", client.usedCalls());
        m.put("남은 호출", client.remainingCalls());
        return m;
    }

    /* =========================================================
       upsert
       ========================================================= */
    private void upsert(int type, JsonNode item, String sigCd, double[] xy, Counter c) {
        String cid = text(item, "contentid");
        if (cid == null || cid.isBlank()) { c.unmapped++; return; }
        switch (type) {
            case CT_ATTRACTION, CT_CULTURE, CT_LEISURE, CT_LODGING, CT_SHOPPING, CT_FESTIVAL -> {
                if (attractionRepository.existsBySourceContentId(cid)) { c.skipped++; return; }
                Attraction a = new Attraction();
                a.setSigCd(sigCd);
                a.setName(text(item, "title"));
                a.setType(ATTRACTION_TYPE_NAMES.getOrDefault(type, "관광지"));
                a.setAddr(addr(item));
                a.setLng(xy[0]);
                a.setLat(xy[1]);
                a.setSourceContentId(cid);
                a.setImage(firstNonBlank(text(item, "firstimage"), text(item, "firstimage2")));
                a.setTel(text(item, "tel"));   // 목록 API 에 이미 들어 있어 추가 호출이 들지 않는다
                if (type == CT_FESTIVAL) {
                    // searchFestival 응답에만 들어 있다. 지난 축제를 걸러내려면 이 값이 필요하다.
                    a.setEventStartDate(text(item, "eventstartdate"));
                    a.setEventEndDate(text(item, "eventenddate"));
                }
                attractionRepository.save(a);
                c.countByType.merge(a.getType(), 1, Integer::sum);
                c.attractions++;
            }
            case CT_FOOD -> {
                // 이미 적재된 행이라도 사진이 비어 있으면 채운다 —
                // 음식점 8,540건이 image 컬럼 없이 먼저 들어와서 카드 한 자리가 늘 비어 있었다.
                FoodPlace existing = foodPlaceRepository.findFirstBySourceContentId(cid).orElse(null);
                if (existing != null) {
                    String img = firstNonBlank(text(item, "firstimage"), text(item, "firstimage2"));
                    if (existing.getImage() == null && img != null && !img.isBlank()) {
                        existing.setImage(img);
                        foodPlaceRepository.save(existing);
                        c.foodImages++;
                    } else {
                        c.skipped++;
                    }
                    return;
                }
                FoodPlace f = new FoodPlace();
                f.setSigCd(sigCd);
                f.setName(text(item, "title"));
                f.setCategory(text(item, "cat3"));
                f.setAddr(addr(item));
                f.setLng(xy[0]);
                f.setLat(xy[1]);
                f.setSourceContentId(cid);
                f.setImage(firstNonBlank(text(item, "firstimage"), text(item, "firstimage2")));
                foodPlaceRepository.save(f);
                c.foodPlaces++;
            }
            case CT_COURSE -> {
                if (travelCourseRepository.existsBySourceContentId(cid)) { c.skipped++; return; }
                TravelCourse tc = new TravelCourse();
                tc.setSigCd(sigCd);
                tc.setTitle(text(item, "title"));
                tc.setTheme(text(item, "cat2"));
                tc.setSourceContentId(cid);
                fillCoursePoints(tc, cid);
                travelCourseRepository.save(tc);
                c.travelCourses++;
            }
            default -> { }
        }
    }

    /**
     * 여행코스 경유지(detailInfo2) — best-effort.
     * 코스 1건마다 호출 1회를 더 쓰므로, 남은 예산이 넉넉할 때만 채운다.
     * (경유지는 없어도 코스 자체는 저장되고, 나중에 다시 채울 수 있다)
     */
    private void fillCoursePoints(TravelCourse tc, String cid) {
        if (client.remainingCalls() < COURSE_DETAIL_MIN_REMAINING) {
            return;
        }
        try {
            List<JsonNode> sub = client.detailInfo(cid, CT_COURSE);
            List<CoursePoint> points = new ArrayList<>();
            int order = 1;
            for (JsonNode s : sub) {
                CoursePoint cp = new CoursePoint();
                cp.setOrder(order++);
                cp.setName(text(s, "subname"));
                cp.setDescription(text(s, "subdetailoverview"));
                // 같은 응답에 들어 있어 추가 호출 없이 얻는 값들
                cp.setContentId(text(s, "subcontentid"));
                cp.setImage(text(s, "subdetailimg"));
                points.add(cp);
            }
            // 응답이 비면(일시적 오류·rate limit 포함) 기존 경유지를 지우지 않는다.
            // 빈 목록으로 덮어쓰면 이미 적재해둔 경유지가 사라진다.
            if (points.isEmpty()) {
                log.debug("[TourSync] 경유지 응답 없음 — 기존 값 유지 cid={}", cid);
                return;
            }
            tc.setPoints(points);
        } catch (Exception e) {
            log.debug("[TourSync] 코스 경유지 조회 실패 cid={}: {}", cid, e.getMessage());
        }
    }

    /* ---------- 유틸 ---------- */
    private double[] coord(JsonNode item) {
        Double x = parse(text(item, "mapx"));
        Double y = parse(text(item, "mapy"));
        if (x == null || y == null || x == 0.0 || y == 0.0) return null;
        return new double[]{x, y};
    }

    private String addr(JsonNode item) {
        String a1 = text(item, "addr1");
        String a2 = text(item, "addr2");
        if (a1 == null) return a2;
        return (a2 == null || a2.isBlank()) ? a1 : a1 + " " + a2;
    }

    private String text(JsonNode item, String field) {
        JsonNode n = item.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    private String title(JsonNode item) {
        String t = text(item, "title");
        return t == null ? "?" : t;
    }

    private Double parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null && !b.isBlank() ? b : null);
    }

    private String sidoPrefixOf(int areaCode) {
        return SIDO_TO_AREA.entrySet().stream()
                .filter(e -> e.getValue() == areaCode)
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private void sleep() {
        try {
            Thread.sleep(CALL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> summary(String scope, Counter c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope);
        m.put("attractions", c.attractions);
        m.put("attractionsByType", c.countByType);
        m.put("foodPlaces", c.foodPlaces);
        m.put("foodImagesFilled", c.foodImages);
        m.put("travelCourses", c.travelCourses);
        m.put("skipped(existing)", c.skipped);
        m.put("outsidePolygon", c.outside);
        m.put("noCoord", c.unmapped);
        return m;
    }

    private static final class Counter {
        int attractions, foodPlaces, foodImages, travelCourses, skipped, outside, unmapped;
        boolean stoppedByBudget;
        /** Attraction 으로 적재된 건수를 type 별로 나눠 본다(관광지/문화시설/숙박/축제/…) */
        final Map<String, Integer> countByType = new LinkedHashMap<>();
    }
}

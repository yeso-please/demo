package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.DayPlan;
import com.sunz.hidden_travel.domain.GoodPriceShop;
import com.sunz.hidden_travel.domain.FoodPlace;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.TripDay;
import com.sunz.hidden_travel.domain.TripItem;
import com.sunz.hidden_travel.domain.TripPlan;
import com.sunz.hidden_travel.repository.FoodPlaceRepository;
import com.sunz.hidden_travel.repository.GoodPriceShopRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.TripPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 여러 날 여행 계획의 생성·편집·저장 (PRD v4 F-05 · F-06 · F-08).
 *
 * <p><b>생성 규칙</b>
 * <ul>
 *   <li>관광지 중심으로 조립한다. 식당은 자동으로 넣지 않고 '점심 60분' 같은 시간 블록만 둔다(§7.2).</li>
 *   <li>같은 장소를 여러 Day 에 넣지 않는다(F-05). 회차를 넘겨가며 안 쓴 장소를 모은다.</li>
 *   <li>후보가 모자라면 <b>빈 Day 를 그대로 둔다.</b> 채우려고 다른 지역으로 넘어가지 않는다(F-04).</li>
 *   <li>이동은 좌표 직선거리 기반 <b>추정</b>이다. 좌표가 없으면 null 로 두고 0분으로 세지 않는다(F-07).</li>
 * </ul>
 */
@Service
public class TripPlanService {

    private static final Logger log = LoggerFactory.getLogger(TripPlanService.class);

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 7;

    /** 하루에 담는 관광지 수 상한 — 장소 수만 채운 과밀 일정을 막는다(PRD §14) */
    private static final int MAX_SIGHTS_PER_DAY = 4;

    /** 빈 Day 를 만들지 않으려고 회차를 넘겨보는 횟수 상한 */
    private static final int MAX_VARIANT_SCAN = 12;

    /** 점심 블록 기본 길이(분) */
    private static final int LUNCH_MINUTES = 60;

    /** 먹거리 후보를 찾는 반경(km) */
    private static final double FOOD_RADIUS_KM = 12.0;

    private final TripPlanRepository tripPlanRepository;
    private final RegionRepository regionRepository;
    private final DayPlanService dayPlanService;
    private final FoodPlaceRepository foodPlaceRepository;
    private final GoodPriceShopRepository goodPriceShopRepository;

    public TripPlanService(TripPlanRepository tripPlanRepository,
                           RegionRepository regionRepository,
                           DayPlanService dayPlanService,
                           FoodPlaceRepository foodPlaceRepository,
                           GoodPriceShopRepository goodPriceShopRepository) {
        this.tripPlanRepository = tripPlanRepository;
        this.regionRepository = regionRepository;
        this.dayPlanService = dayPlanService;
        this.foodPlaceRepository = foodPlaceRepository;
        this.goodPriceShopRepository = goodPriceShopRepository;
    }

    /* =========================================================
       생성
       ========================================================= */

    @Transactional
    public TripPlan create(String sigCd, int dayCount, LocalDate startDate, TripPlan.Entry entry,
                           Long userId, String guestKey, TravelPreferenceService.Snapshot pref) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        String regionName = region != null ? region.getName() : "이 지역";

        int days = Math.clamp(dayCount, MIN_DAYS, MAX_DAYS);

        TripPlan plan = new TripPlan();
        plan.setUserId(userId);
        plan.setGuestKey(userId == null ? guestKey : null);
        plan.setSigCd(sigCd);
        plan.setDayCount(days);
        plan.setStartDate(startDate);
        plan.setEntry(entry == null ? TripPlan.Entry.DIRECT : entry);
        plan.setTitle(regionName + "에서 " + nightsText(days));
        if (pref != null) {
            plan.setMbtiCode(pref.mbtiCode());
            plan.setPreferenceTags(String.join(",", pref.tags()));
            plan.setPreferenceAnswered(pref.answered());
        }

        fillDays(plan, sigCd, days, pref);
        return tripPlanRepository.save(plan);
    }

    /**
     * Day 를 관광지로 채운다. 이미 쓴 장소는 건너뛰므로 뒤쪽 Day 일수록 후보가 줄어든다 —
     * 모자라면 빈 Day 로 남기고 화면이 그 사실을 알린다.
     */
    private void fillDays(TripPlan plan, String sigCd, int days, TravelPreferenceService.Snapshot pref) {
        Set<String> used = new HashSet<>();
        int variant = 0;

        for (int i = 1; i <= days; i++) {
            TripDay day = new TripDay(i);
            plan.addDay(day);

            List<DayPlan.Stop> picked = new ArrayList<>();
            // 회차를 넘겨가며 아직 안 쓴 관광지를 모은다
            while (picked.size() < MAX_SIGHTS_PER_DAY && variant < MAX_VARIANT_SCAN) {
                DayPlan generated = dayPlanService.plan(sigCd, variant);
                variant++;
                if (!generated.available() || !generated.hasStops()) {
                    continue;
                }
                for (DayPlan.Stop s : generated.stops()) {
                    // 관광지만 담는다 — 식당은 사용자가 직접 고른다(PRD §7.2)
                    if (!"attraction".equals(s.dataType())) {
                        continue;
                    }
                    if (s.name() == null || used.contains(s.name())) {
                        continue;
                    }
                    if (excludedByPreference(s, pref)) {
                        continue;
                    }
                    used.add(s.name());
                    picked.add(s);
                    if (picked.size() >= MAX_SIGHTS_PER_DAY) {
                        break;
                    }
                }
            }

            appendStops(day, picked);
        }
    }

    /**
     * 명시적 제외 조건은 자동으로 풀지 않는다(PRD 6.2).
     * 지금은 장소 설명·유형 텍스트에 걸리는 단어만 본다 — 한계를 알고 쓰는 거친 필터다.
     */
    private boolean excludedByPreference(DayPlan.Stop stop, TravelPreferenceService.Snapshot pref) {
        if (pref == null || pref.excludes() == null || pref.excludes().isEmpty()) {
            return false;
        }
        String text = (stop.name() + " " + nullToEmpty(stop.category()) + " " + nullToEmpty(stop.description()))
                .toLowerCase();
        for (String ex : pref.excludes()) {
            if ("물놀이".equals(ex) && (text.contains("해수욕") || text.contains("수영") || text.contains("워터"))) {
                return true;
            }
            if ("계단·경사 많은 곳".equals(ex) && (text.contains("등산") || text.contains("전망대") || text.contains("산성"))) {
                return true;
            }
            if ("오래 걷기".equals(ex) && (text.contains("둘레길") || text.contains("종주") || text.contains("트레킹"))) {
                return true;
            }
        }
        return false;
    }

    /** 고른 관광지를 순서대로 넣고, 중간에 점심 블록을 하나 끼운다 */
    private void appendStops(TripDay day, List<DayPlan.Stop> stops) {
        if (stops.isEmpty()) {
            return;
        }
        DayPlan.Stop previous = null;
        int lunchAfter = Math.min(2, stops.size());  // 두 곳을 본 뒤 점심

        for (int i = 0; i < stops.size(); i++) {
            DayPlan.Stop s = stops.get(i);

            TripItem item = new TripItem();
            item.setKind(TripItem.Kind.SIGHT);
            item.setName(s.name());
            item.setCategory(s.category() != null ? s.category() : "관광지");
            item.setDataType("attraction");
            item.setSage(s.sage());
            item.setAttractionId(s.attractionId());
            item.setImage(s.image());
            item.setAddr(s.addr());
            item.setLat(s.lat());
            item.setLng(s.lng());
            item.setStayMinutes(s.stayMinutes() > 0 ? s.stayMinutes() : 60);
            item.setHoursUnverified(!s.hoursVerified());
            item.setDescription(s.description());
            // 좌표가 둘 다 있어야 추정할 수 있다. 없으면 null 로 두고 화면이 '확인 필요'를 띄운다
            item.setLegMinutes(estimateLeg(previous, s));
            day.addItem(item);
            previous = s;

            if (i + 1 == lunchAfter) {
                TripItem meal = new TripItem();
                meal.setKind(TripItem.Kind.MEAL);
                meal.setName("점심 시간");
                meal.setCategory("식사");
                meal.setStayMinutes(LUNCH_MINUTES);
                // 식사는 자리를 옮기는 구간이 아니다 — '이동시간 모름'이 아니라 이동 자체가 없다
                meal.setLegMinutes(0);
                meal.setDescription("식당은 직접 고를 수 있게 시간만 비워뒀어요. 아래 먹거리 칸에서 찾아보세요.");
                day.addItem(meal);
            }
        }
    }

    private Integer estimateLeg(DayPlan.Stop from, DayPlan.Stop to) {
        if (from == null) {
            return 0;   // 첫 자리는 이동 구간이 없다
        }
        if (from.lat() == null || from.lng() == null || to.lat() == null || to.lng() == null) {
            return null;  // 모른다 — 0분으로 바꾸지 않는다
        }
        return DayPlanService.estimateMinutes(from.lat(), from.lng(), to.lat(), to.lng());
    }

    /* =========================================================
       조회 · 권한
       ========================================================= */

    @Transactional(readOnly = true)
    public TripPlan find(Long id) {
        return id == null ? null : tripPlanRepository.findById(id).orElse(null);
    }

    /**
     * 이 계획을 열어볼 수 있는가.
     * 회원 계획은 소유자만, 비회원 초안은 만든 세션에서만 연다 — id 만 바꿔 남의 계획을 열 수 없다.
     */
    public boolean canAccess(TripPlan plan, Long userId, String guestKey) {
        if (plan == null) {
            return false;
        }
        if (plan.getUserId() != null) {
            return plan.getUserId().equals(userId);
        }
        return plan.getGuestKey() != null && plan.getGuestKey().equals(guestKey);
    }

    @Transactional(readOnly = true)
    public List<TripPlan> myTrips(Long userId, String guestKey) {
        if (userId != null) {
            return tripPlanRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }
        return guestKey == null ? List.of() : tripPlanRepository.findByGuestKeyOrderByUpdatedAtDesc(guestKey);
    }

    /** 로그인하면서 이 세션의 비회원 초안을 계정으로 옮긴다 (PRD F-01 · F-10 이관) */
    @Transactional
    public int claimGuestPlans(Long userId, String guestKey) {
        if (userId == null || guestKey == null) {
            return 0;
        }
        List<TripPlan> plans = tripPlanRepository.findByGuestKeyOrderByUpdatedAtDesc(guestKey);
        plans.forEach(p -> {
            p.setUserId(userId);
            p.setGuestKey(null);
        });
        return plans.size();
    }

    /* =========================================================
       편집
       ========================================================= */

    /** 저장 결과. 충돌이면 최신 버전을 함께 돌려주고 화면은 사용자의 편집을 버리지 않는다 */
    public record SaveResult(boolean ok, boolean conflict, int version, String message) {}

    /**
     * Day 별 항목을 통째로 교체한다.
     *
     * @param expectedVersion 클라이언트가 들고 있던 버전. 다르면 저장하지 않고 충돌을 알린다
     */
    @Transactional
    public SaveResult saveItems(TripPlan plan, int expectedVersion, List<DayPayload> payload) {
        if (plan == null) {
            return new SaveResult(false, false, 0, "계획을 찾을 수 없어요.");
        }
        if (plan.getVersion() != expectedVersion) {
            return new SaveResult(false, true, plan.getVersion(),
                    "다른 사람이 먼저 저장했어요. 최신 일정을 불러온 뒤 다시 저장해 주세요.");
        }

        for (DayPayload dp : payload) {
            TripDay day = plan.day(dp.dayIndex());
            if (day == null) {
                continue;
            }
            day.getItems().clear();
            int order = 1;
            TripItem previousPlace = null;   // 좌표가 있는 직전 장소
            for (ItemPayload ip : dp.items()) {
                if (ip.name() == null || ip.name().isBlank()) {
                    continue;
                }
                TripItem item = new TripItem();
                item.setKind(parseKind(ip.kind()));
                item.setName(ip.name().trim());
                item.setCategory(ip.category());
                item.setDataType(ip.dataType());
                item.setSage(ip.sage());
                item.setAttractionId(ip.attractionId());
                item.setImage(ip.image());
                item.setAddr(ip.addr());
                item.setLat(ip.lat());
                item.setLng(ip.lng());
                item.setStayMinutes(Math.clamp(ip.stayMinutes(), 10, 600));
                item.setHoursUnverified(ip.hoursUnverified());
                item.setDescription(ip.description());
                item.setNote(ip.note());
                item.setSortOrder(order++);
                // 순서가 바뀌면 이동 추정도 다시 계산한다 — 옛 값을 최신처럼 보여주지 않는다
                item.setLegMinutes(recomputeLeg(previousPlace, item));
                item.setDay(day);
                day.getItems().add(item);
                if (item.hasCoord()) {
                    previousPlace = item;
                }
            }
        }

        plan.setVersion(plan.getVersion() + 1);
        plan.setUpdatedAt(LocalDateTime.now());
        // 확정한 계획을 고치면 초안으로 돌아간다(PRD F-06)
        plan.setStatus(TripPlan.Status.DRAFT);
        return new SaveResult(true, false, plan.getVersion(), null);
    }

    /**
     * 직전 <b>장소</b>에서 여기까지의 이동 추정.
     *
     * <p>식사·휴식 블록은 자리를 옮기는 구간이 아니므로 0분이다 — '모른다'(null)와 구분한다.
     * 좌표가 없는 장소만 null 이 되고, 화면은 그 구간을 '확인 필요'로 표시한다.
     */
    private Integer recomputeLeg(TripItem previousPlace, TripItem current) {
        if (current.getKind() != TripItem.Kind.SIGHT) {
            return 0;
        }
        if (previousPlace == null) {
            return 0;   // 그 Day 의 첫 장소
        }
        if (!current.hasCoord()) {
            return null;
        }
        return DayPlanService.estimateMinutes(previousPlace.getLat(), previousPlace.getLng(),
                current.getLat(), current.getLng());
    }

    private TripItem.Kind parseKind(String raw) {
        try {
            return raw == null ? TripItem.Kind.SIGHT : TripItem.Kind.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TripItem.Kind.SIGHT;
        }
    }

    @Transactional
    public boolean setStay(TripPlan plan, int dayIndex, String name, String addr, String url,
                           Double lat, Double lng) {
        TripDay day = plan == null ? null : plan.day(dayIndex);
        if (day == null) {
            return false;
        }
        day.setStayName(blankToNull(name));
        day.setStayAddr(blankToNull(addr));
        day.setStayUrl(safeUrl(url));
        day.setStayLat(lat);
        day.setStayLng(lng);
        touch(plan);
        return true;
    }

    @Transactional
    public boolean setFood(TripPlan plan, int dayIndex, String name, String addr, boolean sage,
                           String priceText, Double lat, Double lng) {
        TripDay day = plan == null ? null : plan.day(dayIndex);
        if (day == null) {
            return false;
        }
        day.setFoodName(blankToNull(name));
        day.setFoodAddr(blankToNull(addr));
        day.setFoodSage(sage);
        day.setFoodPriceText(blankToNull(priceText));
        day.setFoodLat(lat);
        day.setFoodLng(lng);

        // 비워둔 식사 블록에 고른 가게 이름을 붙인다 — 시간과 장소가 따로 놀지 않게
        day.getItems().stream()
                .filter(i -> i.getKind() == TripItem.Kind.MEAL)
                .findFirst()
                .ifPresent(meal -> {
                    if (day.hasFood()) {
                        meal.setName("점심 · " + day.getFoodName());
                        meal.setDescription("아래 먹거리 칸에서 직접 고른 곳이에요. 영업일과 예약은 직접 확인해 주세요.");
                    } else {
                        meal.setName("점심 시간");
                        meal.setDescription("식당은 직접 고를 수 있게 시간만 비워뒀어요. 아래 먹거리 칸에서 찾아보세요.");
                    }
                });
        touch(plan);
        return true;
    }

    @Transactional
    public boolean confirm(TripPlan plan) {
        if (plan == null || !plan.canConfirm()) {
            return false;
        }
        plan.setStatus(TripPlan.Status.CONFIRMED);
        touch(plan);
        return true;
    }

    @Transactional
    public void rename(TripPlan plan, String title) {
        if (plan == null || title == null || title.isBlank()) {
            return;
        }
        plan.setTitle(title.trim());
        touch(plan);
    }

    private void touch(TripPlan plan) {
        plan.setVersion(plan.getVersion() + 1);
        plan.setUpdatedAt(LocalDateTime.now());
    }

    /* =========================================================
       먹거리 후보 — '주변 먹거리 찾기'
       ========================================================= */

    /**
     * 그 Day 동선 위 마지막 장소를 기준으로 가까운 먹거리를 모은다.
     *
     * <p>좌표 평균을 기준으로 삼으면 바다·산속에 점이 찍힌다(PRD F-08). 실제 장소를 기준점으로 쓴다.
     * 좌표가 없는 착한가격업소는 거리로 거르지 못하므로 뒤에 붙이고 화면에서 그 사실을 밝힌다.
     */
    @Transactional(readOnly = true)
    public List<FoodCandidate> foodCandidates(String sigCd, Double baseLat, Double baseLng, int limit) {
        List<FoodCandidate> out = new ArrayList<>();

        for (FoodPlace f : foodPlaceRepository.findBySigCd(sigCd)) {
            Double km = distanceKm(baseLat, baseLng, f.getLat(), f.getLng());
            if (km != null && km > FOOD_RADIUS_KM) {
                continue;
            }
            // 원본은 TourAPI 분류 코드(A05020100)라 사람이 읽는 라벨로 바꾼다
            out.add(new FoodCandidate(f.getName(), FoodCategories.label(f.getCategory()), f.getAddr(), false, null,
                    f.getLat(), f.getLng(), km, f.getImage()));
        }

        for (GoodPriceShop s : goodPriceShopRepository.findBySigCd(sigCd)) {
            Double km = distanceKm(baseLat, baseLng, s.getLat(), s.getLng());
            if (km != null && km > FOOD_RADIUS_KM) {
                continue;
            }
            String price = s.getPrice() != null ? String.format("1인 %,d원", s.getPrice()) : null;
            out.add(new FoodCandidate(s.getName(), s.getCategory(), s.getAddr(), true, price,
                    s.getLat(), s.getLng(), km, null));
        }

        // 가까운 순. 거리를 모르는 곳은 뒤로 — 모르는 것을 가까운 척하지 않는다
        out.sort(Comparator.comparingDouble(c -> c.distanceKm() == null ? Double.MAX_VALUE : c.distanceKm()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private Double distanceKm(Double baseLat, Double baseLng, Double lat, Double lng) {
        if (baseLat == null || baseLng == null || lat == null || lng == null) {
            return null;
        }
        return DayPlanService.estimateRoadKm(baseLat, baseLng, lat, lng);
    }

    /**
     * @param distanceKm 기준점에서의 거리 추정. 좌표가 없으면 null — 화면에 '거리 모름'으로 표시한다
     */
    public record FoodCandidate(String name, String category, String addr, boolean sage,
                                String priceText, Double lat, Double lng, Double distanceKm,
                                String image) {

        public String distanceText() {
            if (distanceKm == null) {
                return null;
            }
            return distanceKm < 1 ? Math.round(distanceKm * 1000) + "m" : String.format("%.1fkm", distanceKm);
        }
    }

    /* =========================================================
       저장 요청 페이로드
       ========================================================= */

    public record DayPayload(int dayIndex, List<ItemPayload> items) {}

    public record ItemPayload(String kind, String name, String category, String dataType, boolean sage,
                              Long attractionId, String image, String addr, Double lat, Double lng,
                              int stayMinutes, boolean hoursUnverified, String description, String note) {}

    /* =========================================================
       작은 도우미
       ========================================================= */

    private static String nightsText(int days) {
        return days <= 1 ? "보내는 하루" : (days - 1) + "박 " + days + "일";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 외부 링크는 http(s) 만 받는다 — javascript: 같은 스킴을 그대로 두면 클릭이 위험해진다 */
    private static String safeUrl(String url) {
        String v = blankToNull(url);
        if (v == null) {
            return null;
        }
        String lower = v.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            log.warn("[TripPlan] 허용하지 않는 링크 스킴이라 저장하지 않습니다: {}", v);
            return null;
        }
        return v;
    }
}

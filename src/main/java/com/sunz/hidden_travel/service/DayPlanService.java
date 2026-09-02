package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.CourseInitItem;
import com.sunz.hidden_travel.controller.dto.DayPlan;
import com.sunz.hidden_travel.domain.Attraction;
import com.sunz.hidden_travel.domain.FoodPlace;
import com.sunz.hidden_travel.domain.GoodPriceShop;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.Specialty;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.repository.FoodPlaceRepository;
import com.sunz.hidden_travel.repository.GoodPriceShopRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.SpecialtyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * '이 지역에서 하루 보내기' — 시군구 하나를 오전·점심·오후·저녁 코스로 자동 조립한다.
 *
 * <h2>왜 필요한가</h2>
 * 지도에서 처음 보는 지역을 발견해도 보상이 "몰랐던 지역을 봤다"에서 끝나면 다시 오지 않는다.
 * 발견 직후 <b>실제로 움직일 수 있는 하루</b>가 나와야 낯선 선택이 실행으로 이어진다.
 *
 * <h2>조립 규칙</h2>
 * <pre>
 * 오전  관광지         사진이 있는 곳 우선 (카드가 비어 보이지 않게)
 * 점심  착한가격업소   ← 없으면 일반 음식점
 * 오후  관광지         점심에서 가장 가까운 다른 곳
 * 저녁  음식점         ← 없으면 남은 착한가격업소
 * </pre>
 * 각 자리는 <b>직전 장소에서 가장 가까운 곳</b>으로 잇고, 한 구간이 {@value #MAX_LEG_KM}km 를
 * 넘으면 그 자리를 비운다. 하루에 무리인 동선을 그럴듯하게 채워 넣지 않기 위해서다
 * (그래서 결과가 늘 네 자리인 것은 아니다).
 *
 * <h2>착한가격업소에는 좌표가 없다</h2>
 * 행정안전부 원본에 위경도가 없어 {@code GoodPriceShop.lat/lng} 는 전부 비어 있다
 * (주소만 있다). 거리로 고를 수 없으므로 <b>한 곳까지만</b> 좌표 없이 담고,
 * 나머지 자리는 좌표가 있는 곳으로 잇는다. 이동 추정에서 빠진다는 사실은 화면에 적는다.
 * <p>가격 근거를 포기하는 것보다 낫다 — 낯선 지역에서 가장 큰 회피 요인이 가격이고,
 * 주소가 있으니 길찾기는 된다. (지오코딩이 붙으면 이 예외는 사라진다)
 * 점심에 착한가격업소를 우선 넣는 것은 우연이 아니다. 낯선 지역의 최대 회피 요인이
 * 가격 불확실성이므로, 공시 가격이 있는 식당을 코스에 구조적으로 포함시킨다(PRD 04-기능4).
 *
 * <h2>외부 호출 없음</h2>
 * 이동 거리·시간은 좌표 직선거리에 도로 계수를 곱한 <b>추정치</b>다.
 * 카카오모빌리티 길찾기는 300회/일 한도(DATA §7)라 코스를 열 때마다 부를 수 없다 —
 * 실제 경로는 사용자가 저장할 때 {@code CourseRouteService} 가 1회 계산한다.
 * 그래서 화면에는 반드시 "추정"이라고 적는다.
 */
@Service
public class DayPlanService {

    /**
     * 하루 코스의 경유지로 쓸 수 있는 Attraction 유형.
     *
     * Attraction 테이블에는 숙박·쇼핑·축제도 함께 적재된다.
     * 숙박은 코스 중간에 들어갈 곳이 아니고, 축제는 기간이 지나면 갈 수 없으므로 제외한다.
     * (축제를 코스에 넣으려면 eventStartDate/eventEndDate 로 기간을 먼저 걸러야 한다)
     */
    private static final java.util.Set<String> COURSE_STOP_TYPES =
            java.util.Set.of("관광지", "문화시설", "레포츠");

    /** 직선거리 → 실제 도로 거리 보정 계수 */
    private static final double ROAD_FACTOR = 1.3;

    /** 지방도 기준 평균 이동 속도(km/h) */
    private static final double AVG_SPEED_KMH = 40.0;

    /** 한 구간이 이 거리를 넘으면 하루 코스로 무리다 — 그 자리는 비운다 */
    private static final double MAX_LEG_KM = 30.0;

    /** '모여 있다'고 말할 수 있는 반경 — 이유 문장 계산에 쓴다 */
    private static final double CLUSTER_RADIUS_KM = 1.5;

    /** 밀집을 근거로 내세우려면 이만큼은 모여 있어야 한다 */
    private static final int CLUSTER_MIN_COUNT = 3;

    /** 하루라고 부르려면 최소 두 곳 */
    private static final int MIN_STOPS = 2;

    /**
     * 코스 길이. <b>반나절은 하루를 자른 게 아니라 성격이 다르다.</b>
     *
     * <pre>
     * 하루    이동 1~2시간 → 관광지 2~3곳 + 식사 2회   (어디로 갈까)
     * 반나절  이동 30분     → 한 군데 깊게 + 한 끼/카페 (지금 나갈까)
     * </pre>
     *
     * 그래서 반나절은 슬롯 수만 줄이지 않고 <b>고르는 기준 자체를 바꾼다</b> —
     * 걷기 좋은 곳을 먼저 잡고, 그 옆에 앉을 자리를 붙인다.
     */
    public enum Length { HALF, DAY }

    /** 반나절에서 먼저 집는 '걷기 좋은' 장소의 단서 — 이름으로만 판정한다(설명은 15%만 확보돼 있다) */
    private static final List<String> WALKABLE_HINTS =
            List.of("길", "공원", "산책", "둘레", "숲", "천변", "하천", "호수", "저수지",
                    "해변", "해안", "강변", "거리", "마을");

    /** 반나절 코스 최대 정거장 — 이보다 많으면 '나들이'가 아니라 하루가 된다 */
    private static final int HALF_MAX_STOPS = 3;

    private static final int MAX_REASONS = 3;

    /** 이용시간 원문은 길고 태그가 섞여 있다 — 요약해 보여줄 길이 */
    private static final int HOURS_MAX_LEN = 60;

    /** 카드에 넣을 소개 길이 — 이보다 길면 카드가 목록이 아니라 글이 된다 */
    private static final int DESC_MAX_LEN = 110;

    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final FoodPlaceRepository foodPlaceRepository;
    private final GoodPriceShopRepository goodPriceShopRepository;
    private final SpecialtyRepository specialtyRepository;

    public DayPlanService(RegionRepository regionRepository,
                          AttractionRepository attractionRepository,
                          FoodPlaceRepository foodPlaceRepository,
                          GoodPriceShopRepository goodPriceShopRepository,
                          SpecialtyRepository specialtyRepository) {
        this.regionRepository = regionRepository;
        this.attractionRepository = attractionRepository;
        this.foodPlaceRepository = foodPlaceRepository;
        this.goodPriceShopRepository = goodPriceShopRepository;
        this.specialtyRepository = specialtyRepository;
    }

    /* =========================================================
       조립
       ========================================================= */

    /**
     * 하루 코스를 만든다.
     *
     * @param variant 같은 지역에서 다른 조합을 보고 싶을 때 올린다.
     *                같은 값이면 항상 같은 결과가 나온다(새로고침으로 코스가 바뀌면 신뢰가 깨진다).
     */
    @Transactional(readOnly = true)
    public DayPlan plan(String sigCd, int variant) {
        return plan(sigCd, variant, Length.DAY);
    }

    /**
     * 반나절 코스. 걷기 좋은 곳 하나를 중심으로 잡고 앉을 자리를 붙인다.
     *
     * <p>하루 코스를 잘라 쓰지 않는 이유는 {@link Length} 주석에 적었다.
     * 여기서 만든 제목은 코스가 실제로 가진 성격을 옮긴 문장이라 사실과 어긋날 수 없다
     * (LLM 으로 생성하지 않는다 — PRD 제품원칙 4).
     */
    @Transactional(readOnly = true)
    public DayPlan planHalf(String sigCd, int variant) {
        return plan(sigCd, variant, Length.HALF);
    }

    @Transactional(readOnly = true)
    public DayPlan plan(String sigCd, int variant, Length length) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        String name = region != null ? region.getName() : "이 지역";
        String province = region != null ? region.getProvince() : "";

        List<Attraction> attractions = attractionRepository.findBySigCd(sigCd);
        List<FoodPlace> foods = foodPlaceRepository.findBySigCd(sigCd);
        List<GoodPriceShop> shops = goodPriceShopRepository.findBySigCd(sigCd);

        List<Place> spots = attractions.stream()
                .filter(a -> hasCoord(a.getLat(), a.getLng()))
                .filter(a -> COURSE_STOP_TYPES.contains(a.getType() == null ? "관광지" : a.getType()))
                .map(DayPlanService::fromAttraction)
                .toList();
        // 착한가격업소는 좌표가 없다 — 걸러내면 가격 근거가 통째로 사라지므로 그대로 둔다
        List<Place> goodPriceMeals = shops.stream()
                .filter(s -> GoodPriceCategories.isFood(s.getCategory()))
                .map(DayPlanService::fromGoodPriceShop)
                .toList();
        List<Place> eateries = foods.stream()
                .filter(f -> hasCoord(f.getLat(), f.getLng()))
                .map(DayPlanService::fromFoodPlace)
                .toList();

        if (spots.isEmpty()) {
            return unavailable(sigCd, name, province, variant,
                    "이 지역에는 좌표가 있는 관광지가 아직 없어서 하루 코스를 자동으로 짤 수 없어요. "
                            + "왼쪽 후보에서 직접 담아 만들어 보세요.");
        }
        if (goodPriceMeals.isEmpty() && eateries.isEmpty()) {
            return unavailable(sigCd, name, province, variant,
                    "이 지역에는 좌표가 있는 식당 데이터가 아직 없어서 하루 코스를 자동으로 짤 수 없어요. "
                            + "왼쪽 후보에서 직접 담아 만들어 보세요.");
        }

        List<Place> picked = new ArrayList<>();
        List<String> slots = new ArrayList<>();
        Set<String> used = new HashSet<>();

        if (length == Length.HALF) {
            return planHalf(sigCd, name, province, variant, spots, eateries, goodPriceMeals, picked, slots, used);
        }

        // 오전 — 사진이 있는 관광지 우선. 회차(variant)로 다른 조합을 뽑는다
        Place morning = pickFirst(spots, sigCd, variant);
        picked.add(morning);
        slots.add("오전");
        used.add(morning.name());

        // 점심 — 착한가격업소를 먼저 본다. 우리 차별점을 코스에 구조적으로 넣는다
        Place lunch = pickGoodPriceMeal(morning, goodPriceMeals, used, sigCd, variant);
        if (lunch == null) {
            lunch = nearestMeal(morning, eateries, used);
        }
        if (lunch != null) {
            picked.add(lunch);
            slots.add("점심");
            used.add(lunch.name());
        }

        // 오후 — 직전 장소에서 가장 가까운 다른 관광지
        Place afternoon = nearest(anchorOf(picked), spots, used);
        if (afternoon != null) {
            picked.add(afternoon);
            slots.add("오후");
            used.add(afternoon.name());
        }

        // 저녁 — 점심에 쓰지 않은 식당.
        // 좌표 없는 곳은 하루에 한 곳까지만 담는다(지도가 비면 코스로서 못 쓴다)
        Place dinner = nearestMeal(anchorOf(picked), eateries, used);
        if (dinner == null && picked.stream().allMatch(DayPlanService::hasCoord)) {
            dinner = pickGoodPriceMeal(anchorOf(picked), goodPriceMeals, used, sigCd, variant);
        }
        if (dinner != null) {
            picked.add(dinner);
            slots.add("저녁");
            used.add(dinner.name());
        }

        if (picked.size() < MIN_STOPS) {
            return unavailable(sigCd, name, province, variant,
                    "이 지역은 장소들이 서로 멀리 떨어져 있어 하루 코스로 묶기 어려웠어요. "
                            + "왼쪽 후보에서 직접 담아 만들어 보세요.");
        }

        // 구간 이동 추정 — 좌표가 없는 자리는 건너뛰고 앞뒤를 잇는다.
        // 구간별 표기는 화면(course.js)이 같은 식으로 다시 계산한다.
        // 사용자가 순서를 바꾸거나 장소를 빼면 값이 달라지기 때문에 서버 값을 고정해 둘 수 없다.
        double totalKm = 0;
        int totalMin = 0;
        Place prev = null;
        List<DayPlan.Stop> stops = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Place p = picked.get(i);
            int leg = 0;
            if (hasCoord(p)) {
                if (prev != null) {
                    totalKm += roadKm(prev, p);
                    leg = legMinutes(prev, p);
                }
                prev = p;
            }
            int stay = stayMinutes(p);
            totalMin += stay + leg;
            OpeningHours.Parsed oh = OpeningHours.parse(p.hoursText());
            stops.add(new DayPlan.Stop(i + 1, slots.get(i), p.name(), p.dataType(), p.category(),
                    p.sage(), p.attractionId(), p.image(), p.addr(), p.lat(), p.lng(),
                    p.priceText(), p.hoursText(), p.hoursText() != null, stay, leg,
                    oh.alwaysOpen(), oh.openMinutes(), oh.closeMinutes(), oh.closedWeekday(),
                    summary(p.description())));
        }

        int hoursCheckable = (int) stops.stream().filter(s -> s.attractionId() != null).count();
        int hoursVerified = (int) stops.stream().filter(DayPlan.Stop::hoursVerified).count();

        // 이동 추정에서 빠진 곳 — 화면이 이 사실을 그대로 알린다
        List<String> noCoord = picked.stream().filter(p -> !hasCoord(p)).map(Place::name).toList();

        return new DayPlan(sigCd, name, province, moodLine(picked), variant, true, null,
                cover(stops),
                stops,
                reasons(sigCd, morning, picked, attractions, foods, totalKm),
                kmText(totalKm), minutesText(totalKm),
                costText(picked), noCoord,
                hoursVerified, hoursCheckable, totalMin, durationOf(totalMin));
    }

    /**
     * 하루 코스를 만들 수 있는 지역인지. 이미 읽어둔 목록을 그대로 받아 <b>추가 조회를 하지 않는다</b>
     * (지역 패널은 이 세 목록을 이미 들고 있다).
     *
     * <p>랜덤 추천·스포트라이트 후보를 거르는 기준으로도 쓸 수 있다 —
     * "눌러도 볼 게 없는 지역"을 노출하지 않기 위해서다.
     */
    public boolean canBuild(List<Attraction> attractions, List<FoodPlace> foods, List<GoodPriceShop> shops) {
        boolean hasSpot = attractions.stream().anyMatch(a -> hasCoord(a.getLat(), a.getLng()));
        if (!hasSpot) {
            return false;
        }
        // 착한가격업소는 좌표가 없어도 담는다(주소로 찾아갈 수 있다)
        return foods.stream().anyMatch(f -> hasCoord(f.getLat(), f.getLng()))
                || shops.stream().anyMatch(s -> GoodPriceCategories.isFood(s.getCategory())
                        && s.getPrice() != null && s.getPrice() > 0);
    }

    /** 하루 코스를 코스 편집기의 초기 타임라인으로 옮긴다 — 사용자가 그대로 고칠 수 있어야 한다 */
    public static List<CourseInitItem> toInitialItems(DayPlan plan) {
        if (plan == null || !plan.available()) {
            return List.of();
        }
        return plan.stops().stream()
                .map(s -> new CourseInitItem(s.order(), s.slot(), s.name(), s.category(), s.dataType(),
                        s.sage(), s.attractionId(), null, s.image(), s.addr(), s.lat(), s.lng(),
                        s.description()))
                .toList();
    }

    /* =========================================================
       고르기
       ========================================================= */

    /**
     * 첫 장소. 사진이 있는 곳을 앞세우되(카드가 비어 보이지 않게),
     * 이름순으로 정렬한 뒤 회차로 인덱스를 잡아 <b>같은 입력이면 같은 결과</b>가 나오게 한다.
     */
    /* =========================================================
       반나절 조립
       ========================================================= */

    /**
     * 걷기 좋은 곳 하나 → 그 옆에 앉을 자리 → (가능하면) 근처 한 곳 더.
     *
     * 슬롯 이름도 시간표(오전·점심)가 아니라 나들이 어휘를 쓴다.
     * 반나절에 "오전/오후"를 붙이면 하루 코스를 자른 것처럼 읽힌다.
     */
    private DayPlan planHalf(String sigCd, String name, String province, int variant,
                             List<Place> spots, List<Place> eateries, List<Place> goodPriceMeals,
                             List<Place> picked, List<String> slots, Set<String> used) {

        Place first = pickWalkable(spots, sigCd, variant);
        if (first == null) {
            first = pickFirst(spots, sigCd, variant);
        }
        if (first == null) {
            return unavailable(sigCd, name, province, variant,
                    "이 지역은 걸어 둘러볼 만한 곳을 찾지 못했어요. 하루 코스로 보시겠어요?");
        }
        picked.add(first);
        slots.add("여기부터");
        used.add(first.name());

        // 앉을 자리 — 카페를 먼저 본다. 반나절에서는 끼니보다 '쉬어가기'가 자연스럽다
        Place rest = nearestCafe(first, eateries, used);
        if (rest == null) rest = pickGoodPriceMeal(first, goodPriceMeals, used, sigCd, variant);
        if (rest == null) rest = nearestMeal(first, eateries, used);
        if (rest != null) {
            picked.add(rest);
            slots.add(rest.cafe() ? "쉬어가기" : "한 끼");
            used.add(rest.name());
        }

        // 여유가 되면 근처 한 곳 더 — 여기서 멈춰야 '나들이'다
        if (picked.size() < HALF_MAX_STOPS) {
            Place more = nearest(anchorOf(picked), spots, used);
            if (more != null) {
                picked.add(more);
                slots.add("걷고 나서");
                used.add(more.name());
            }
        }

        if (picked.size() < MIN_STOPS) {
            return unavailable(sigCd, name, province, variant,
                    "이 지역은 장소가 서로 멀어 반나절로 묶기 어려웠어요.");
        }

        double totalKm = 0;
        int totalMin = 0;
        Place prev = null;
        List<DayPlan.Stop> stops = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Place p = picked.get(i);
            int leg = 0;
            if (hasCoord(p)) {
                if (prev != null) {
                    totalKm += roadKm(prev, p);
                    leg = legMinutes(prev, p);
                }
                prev = p;
            }
            int stay = stayMinutes(p);
            totalMin += stay + leg;
            OpeningHours.Parsed oh = OpeningHours.parse(p.hoursText());
            stops.add(new DayPlan.Stop(i + 1, slots.get(i), p.name(), p.dataType(), p.category(),
                    p.sage(), p.attractionId(), p.image(), p.addr(), p.lat(), p.lng(),
                    p.priceText(), p.hoursText(), p.hoursText() != null, stay, leg,
                    oh.alwaysOpen(), oh.openMinutes(), oh.closeMinutes(), oh.closedWeekday(),
                    summary(p.description())));
        }
        int hoursCheckable = (int) stops.stream().filter(s -> s.attractionId() != null).count();
        int hoursVerified = (int) stops.stream().filter(DayPlan.Stop::hoursVerified).count();
        List<String> noCoord = picked.stream().filter(p -> !hasCoord(p)).map(Place::name).toList();

        return new DayPlan(sigCd, name, province, moodLine(picked), variant, true, null,
                cover(stops),
                stops, halfReasons(name, picked, totalKm),
                kmText(totalKm), minutesText(totalKm), costText(picked), noCoord,
                hoursVerified, hoursCheckable, totalMin, durationOf(totalMin));
    }

    /**
     * 코스 표지 사진 — 담긴 자리 중 사진이 있는 <b>첫 곳</b>.
     *
     * <p>가장 예쁜 사진을 고르고 싶지만 우리에겐 사진의 좋고 나쁨을 판단할 근거가 없다.
     * 그래서 순서를 따른다 — 표지가 코스의 첫 자리와 같아야 "여기서 시작하는구나"로 읽힌다.
     * 전부 사진이 없으면 null (표지를 빼는 편이 회색 자리를 두는 것보다 낫다).
     */
    private static String cover(List<DayPlan.Stop> stops) {
        return stops.stream()
                .map(DayPlan.Stop::image)
                .filter(img -> img != null && !img.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** 이름에 걷기 단서가 있는 곳을 먼저 집는다 */
    private Place pickWalkable(List<Place> spots, String sigCd, int variant) {
        List<Place> walkable = spots.stream()
                .filter(p -> hasCoord(p))
                .filter(p -> {
                    String n = p.name() == null ? "" : p.name();
                    return WALKABLE_HINTS.stream().anyMatch(n::contains);
                })
                .sorted(Comparator.comparing(Place::name))
                .toList();
        if (walkable.isEmpty()) return null;
        return walkable.get(Math.floorMod((sigCd + "#walk#" + variant).hashCode(), walkable.size()));
    }

    private Place nearestCafe(Place from, List<Place> pool, Set<String> used) {
        List<Place> cafes = pool.stream().filter(Place::cafe).filter(p -> !used.contains(p.name())).toList();
        return cafes.isEmpty() ? null : nearest(from, cafes, used);
    }

    /**
     * 코스 성격을 한 문장으로 옮긴다.
     *
     * <b>LLM 을 쓰지 않는다.</b> 이 문장은 코스가 실제로 담고 있는 것(걷는 곳이 있는지,
     * 앉을 자리가 있는지)의 번역이라 사실과 어긋날 수 없다. 생성 문구를 쓰면
     * 없는 계절감·없는 장소가 섞일 수 있고 PRD 제품원칙 4 와 충돌한다.
     */
    private static String moodLine(List<Place> picked) {
        String scene = sceneOf(picked);                       // 공원 / 물가 / 골목 …
        boolean cafe = picked.stream().anyMatch(Place::cafe);
        boolean meal = picked.stream().anyMatch(p -> "food".equals(p.dataType()) && !p.cafe());

        if (scene == null) {
            if (cafe) return "가까운 곳에서 쉬었다 오는 반나절";
            if (meal) return "밥만 먹고 와도 좋은 반나절";
            return "멀리 가지 않는 반나절";
        }
        if (cafe) return scene + " 걷다가 앉기 좋은 반나절";
        if (meal) return scene + " 걷고 한 끼 하는 반나절";
        return scene + " 걷는 데만 쓰는 반나절";
    }

    /**
     * 첫 장소의 이름에서 '어디를 걷는지'를 뽑는다.
     *
     * 문장을 다양하게 만들려는 장치가 아니라 <b>실제로 다른 곳이니 다르게 불러야</b> 하는 것이다.
     * 공원과 해변과 골목은 같은 '산책'이 아니다.
     * 단서가 없으면 null 을 돌려 일반 문장으로 떨어뜨린다 — 없는 분위기를 지어내지 않는다.
     */
    private static String sceneOf(List<Place> picked) {
        for (Place p : picked) {
            String n = p.name() == null ? "" : p.name();
            if (n.contains("해변") || n.contains("해안") || n.contains("항")) return "바다를 끼고";
            if (n.contains("호수") || n.contains("저수지") || n.contains("천변")
                    || n.contains("하천") || n.contains("강변")) return "물가를 따라";
            if (n.contains("숲") || n.contains("수목")) return "숲길을";
            if (n.contains("공원")) return "공원을";
            if (n.contains("둘레") || n.contains("산책")) return "둘레길을";
            if (n.contains("골목") || n.contains("마을") || n.contains("거리")) return "골목을";
            if (n.contains("길")) return "길을";
        }
        return null;
    }

    private List<String> halfReasons(String regionName, List<Place> picked, double totalKm) {
        List<String> out = new ArrayList<>();
        out.add(regionName + "에서 " + picked.size() + "곳만 들르는 짧은 동선이에요.");
        // 거리와 무관하게 "걸어서도"를 붙이면 안 된다 — 8.5km 는 도보 2시간이다.
        // 근거 문장이 한 번 틀리면 나머지 근거도 못 믿게 된다.
        if (totalKm > 0 && totalKm <= 2.0) {
            out.add("장소 사이가 " + kmText(totalKm) + " 라 걸어서 이어집니다.");
        } else if (totalKm > 0) {
            out.add("장소 사이 이동이 " + kmText(totalKm) + " — 차로 " + minutesText(totalKm) + " 걸려요.");
        }
        picked.stream().filter(Place::sage).findFirst().ifPresent(p ->
                out.add("착한가격업소 " + p.name() + "이(가) 코스에 들어 있어요."));
        return out.stream().limit(MAX_REASONS).toList();
    }


    /* =========================================================
       시간 추정
       ========================================================= */

    /**
     * 장소 성격별 머무는 시간(분).
     *
     * <p>이동시간만 보여주면 "13분 코스"처럼 읽혀서 나들이를 정할 수가 없다.
     * 실제로 시간을 잡아먹는 건 이동이 아니라 <b>머무는 시간</b>이다.
     * 값은 추정치이므로 화면에서 '대략'임을 밝힌다 — 정확한 체류 데이터는 어디에도 없다.
     */
    private static int stayMinutes(Place p) {
        if ("food".equals(p.dataType()) || "goodprice".equals(p.dataType())) {
            return p.cafe() ? 40 : 60;
        }
        String n = p.name() == null ? "" : p.name();
        // 걷는 곳은 길게 — 공원·둘레길은 한 바퀴가 곧 목적이다
        if (WALKABLE_HINTS.stream().anyMatch(n::contains)) return 70;
        if (n.contains("박물관") || n.contains("미술관") || n.contains("전시") || n.contains("기념관")) return 60;
        if (n.contains("전망") || n.contains("정자") || n.contains("포구")) return 25;
        return 45;
    }

    /**
     * 소개 문구를 카드에 들어갈 길이로 줄인다.
     *
     * 원문에는 HTML 태그와 줄바꿈이 섞여 있다. 문장 경계에서 자르되,
     * 자를 곳을 못 찾으면 그냥 길이로 자른다 — 없는 문장을 만들지는 않는다.
     */
    private static String summary(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.replaceAll("<[^>]*>", " ").replaceAll("\s+", " ").trim();
        if (t.isEmpty()) return null;
        if (t.length() <= DESC_MAX_LEN) return t;
        int cut = t.lastIndexOf('.', DESC_MAX_LEN);
        if (cut < DESC_MAX_LEN / 2) cut = t.lastIndexOf(' ', DESC_MAX_LEN);
        return (cut > 0 ? t.substring(0, cut) : t.substring(0, DESC_MAX_LEN)).trim() + "…";
    }

    /** 구간 이동 시간(분) — 거리 추정과 같은 계수를 쓴다 */
    private int legMinutes(Place from, Place to) {
        if (from == null || !hasCoord(from) || !hasCoord(to)) return 0;
        double km = roadKm(from, to);
        return (int) Math.max(1, Math.round(km / AVG_SPEED_KMH * 60));
    }

    private static String durationOf(int minutes) {
        if (minutes <= 0) return null;
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "분";
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }

    private Place pickFirst(List<Place> spots, String sigCd, int variant) {
        List<Place> withImage = spots.stream().filter(p -> p.image() != null && !p.image().isBlank()).toList();
        List<Place> pool = withImage.isEmpty() ? spots : withImage;

        List<Place> sorted = pool.stream()
                .sorted(Comparator.comparing(Place::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int index = Math.floorMod((sigCd + '#' + variant).hashCode(), sorted.size());
        return sorted.get(index);
    }

    /** 이미 담은 곳을 빼고, 하루에 무리가 아닌 거리 안에서 가장 가까운 곳 */
    private Place nearest(Place from, List<Place> pool, Set<String> used) {
        if (from == null) {
            return null;
        }
        return pool.stream()
                .filter(p -> !used.contains(p.name()))
                .filter(DayPlanService::hasCoord)
                .filter(p -> roadKm(from, p) <= MAX_LEG_KM)
                .min(Comparator.comparingDouble(p -> roadKm(from, p)))
                .orElse(null);
    }

    /**
     * 끼니 자리에 넣을 가장 가까운 식당.
     * 카페는 뒤로 미룬다 — 점심·저녁 자리에 카페만 들어가면 하루가 되지 않는다.
     */
    private Place nearestMeal(Place from, List<Place> eateries, Set<String> used) {
        Place meal = nearest(from, eateries.stream().filter(p -> !p.cafe()).toList(), used);
        return meal != null ? meal : nearest(from, eateries, used);
    }

    /**
     * 착한가격업소 한 곳.
     *
     * <p>좌표가 있으면 거리로 고르고, 없으면(현재 전량이 그렇다) 가격이 적힌 곳 중에서
     * 회차로 하나를 집는다. 거리를 모르는 채 고르는 것이라 <b>같은 시군구 안</b>이라는
     * 사실에만 기댄다 — 그래서 이 자리는 하루에 한 곳까지만 허용한다.
     */
    private Place pickGoodPriceMeal(Place from, List<Place> pool, Set<String> used, String sigCd, int variant) {
        Place byDistance = nearest(from, pool, used);
        if (byDistance != null) {
            return byDistance;
        }
        List<Place> priced = pool.stream()
                .filter(p -> !used.contains(p.name()))
                .filter(p -> p.price() != null && p.price() > 0)
                .sorted(Comparator.comparing(Place::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (priced.isEmpty()) {
            return null;
        }
        return priced.get(Math.floorMod((sigCd + "#gp#" + variant).hashCode(), priced.size()));
    }

    /** 거리를 잴 기준점 — 마지막으로 담긴 <b>좌표가 있는</b> 장소 */
    private static Place anchorOf(List<Place> picked) {
        for (int i = picked.size() - 1; i >= 0; i--) {
            if (hasCoord(picked.get(i))) {
                return picked.get(i);
            }
        }
        return null;
    }

    /* =========================================================
       이유 — 전부 적재된 데이터에서 계산한다. 지어내지 않는다.
       ========================================================= */

    private List<String> reasons(String sigCd, Place morning, List<Place> picked,
                                 List<Attraction> attractions, List<FoodPlace> foods, double totalKm) {
        List<String> out = new ArrayList<>();

        // 1) 밀집도 — "천천히 걸을 수 있는가"에 대한 답이다
        long near = attractions.stream()
                .filter(a -> hasCoord(a.getLat(), a.getLng()))
                .filter(a -> haversineKm(morning.lat(), morning.lng(), a.getLat(), a.getLng()) <= CLUSTER_RADIUS_KM)
                .count();
        if (near >= CLUSTER_MIN_COUNT) {
            out.add(String.format("%s 반경 %.1fkm 안에 관광지 %d곳이 모여 있어요.",
                    morning.name(), CLUSTER_RADIUS_KM, near));
        }

        // 2) 가격 근거 — 낯선 지역의 최대 회피 요인에 대한 답
        picked.stream()
                .filter(Place::sage)
                .findFirst()
                .ifPresent(p -> out.add(String.format("코스에 담은 %s%s 행정안전부 착한가격업소예요%s.",
                        p.name(), topicParticle(p.name()),
                        p.priceText() != null ? " (" + p.priceText() + ")" : "")));

        // 3) 특산물 — 있는 지역이 전국 24곳뿐이라 있으면 그 자체로 특징이다
        if (out.size() < MAX_REASONS) {
            List<String> specialties = specialtyRepository.findBySigCd(sigCd).stream()
                    .map(Specialty::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .limit(2)
                    .toList();
            if (!specialties.isEmpty()) {
                out.add(String.join("·", specialties) + " 같은 특산물이 있는 지역이에요.");
            }
        }

        // 4) 이동 부담 — 낯선 곳을 고를 때 가장 먼저 재는 것
        if (out.size() < MAX_REASONS && picked.size() > 1) {
            out.add(String.format("%d곳을 도는 이동이 %s 남짓이라 하루에 무리가 없어요(직선거리 추정).",
                    picked.size(), minutesText(totalKm)));
        }

        // 5) 그래도 모자라면 규모로 — 마지막 보루
        if (out.isEmpty()) {
            out.add(String.format("관광지 %d곳, 음식점 %d곳이 등록되어 있어요.", attractions.size(), foods.size()));
        }
        return out.size() > MAX_REASONS ? out.subList(0, MAX_REASONS) : out;
    }

    /**
     * 식비. 착한가격업소 공시 가격만 더한다 — <b>모르는 값을 추정해 채우지 않는다.</b>
     * 입장료·교통비는 데이터가 없어 빠져 있고, 화면이 그 사실을 함께 적는다.
     */
    private String costText(List<Place> picked) {
        int sum = picked.stream()
                .filter(p -> p.price() != null && p.price() > 0)
                .mapToInt(Place::price)
                .sum();
        return sum > 0 ? String.format("1인 %,d원", sum) : null;
    }

    /* =========================================================
       거리·시간 추정
       ========================================================= */

    private double roadKm(Place a, Place b) {
        return haversineKm(a.lat(), a.lng(), b.lat(), b.lng()) * ROAD_FACTOR;
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /**
     * 은/는 조사. 장소 이름이 데이터에서 오므로 "○○은(는)" 같은 표기를 피하려면 직접 골라야 한다.
     * 한글이 아닌 글자로 끝나면(영문·숫자) 판단할 수 없어 "은(는)"으로 물러선다.
     */
    private static String topicParticle(String word) {
        if (word == null || word.isBlank()) {
            return "은(는)";
        }
        char last = word.trim().charAt(word.trim().length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "은(는)";
        }
        return (last - 0xAC00) % 28 == 0 ? "는" : "은";
    }

    private static String kmText(double km) {
        return km < 1 ? Math.round(km * 1000) + "m" : String.format("%.1fkm", km);
    }

    private static String minutesText(double km) {
        int minutes = (int) Math.max(1, Math.round(km / AVG_SPEED_KMH * 60));
        if (minutes < 60) {
            return minutes + "분";
        }
        int rest = minutes % 60;
        return rest > 0 ? (minutes / 60) + "시간 " + rest + "분" : (minutes / 60) + "시간";
    }

    /* =========================================================
       장소 정규화 — 세 테이블을 한 모양으로 다룬다
       ========================================================= */

    /**
     * 관광지·음식점·착한가격업소를 코스에 담을 수 있는 한 가지 모양으로 맞춘 것.
     * 세 엔티티가 공통 타입이 없어 여기서만 쓰는 내부 표현을 둔다.
     */
    private record Place(
            String name, String dataType, String category, boolean sage,
            Long attractionId, String image, String addr,
            /** 착한가격업소는 원본에 위경도가 없어 비어 있다 */
            Double lat, Double lng,
            String priceText, Integer price,
            String hoursText,
            /** 카페·전통찻집 — 끼니 자리에서는 뒤로 미룬다 */
            boolean cafe,
            /** 장소 소개 원문 — 없으면 null */
            String description
    ) {}

    private static boolean hasCoord(Double lat, Double lng) {
        return lat != null && lng != null;
    }

    private static boolean hasCoord(Place p) {
        return p != null && hasCoord(p.lat(), p.lng());
    }

    private static Place fromAttraction(Attraction a) {
        return new Place(a.getName(), "attraction",
                a.getType() != null && !a.getType().isBlank() ? a.getType() : "관광지",
                false, a.getId(), a.getImage(), a.getAddr(), a.getLat(), a.getLng(),
                null, null, hoursOf(a), false, a.getDescription());
    }

    private static Place fromGoodPriceShop(GoodPriceShop s) {
        String price = s.getPrice() != null ? String.format("%,d원", s.getPrice()) : "가격문의";
        String menu = s.getMenu() != null && !s.getMenu().isBlank() ? s.getMenu().trim() + " " : "";
        return new Place(s.getName(), "goodprice",
                s.getCategory() != null && !s.getCategory().isBlank() ? s.getCategory() : "착한가격업소",
                true, null, null, s.getAddr(), s.getLat(), s.getLng(),
                (menu + price).trim(), s.getPrice(), null, false, null);
    }

    private static Place fromFoodPlace(FoodPlace f) {
        return new Place(f.getName(), "food", FoodCategories.label(f.getCategory()),
                false, null, f.getImage(), f.getAddr(), f.getLat(), f.getLng(),
                null, null, shorten(f.getUsetime()), FoodCategories.isCafe(f.getCategory()), f.getDescription());
    }

    /**
     * 이용시간. 상세를 이미 받아둔 관광지만 값이 있다(전체의 극히 일부 — DATA §3).
     * 원문에 태그·줄바꿈이 섞여 있어 한 줄로 줄인다. 없으면 null 이고 화면이 "미확인"으로 표시한다.
     */
    private static String hoursOf(Attraction a) {
        return shorten(a.getUsetime());
    }

    /** 이용시간 원문 정리 — 관광지와 음식점이 같은 규칙을 쓴다 */
    private static String shorten(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= HOURS_MAX_LEN ? text : text.substring(0, HOURS_MAX_LEN) + "…";
    }

    private DayPlan unavailable(String sigCd, String name, String province, int variant, String reason) {
        return new DayPlan(sigCd, name, province, name + "에서 보내는 하루", variant, false, reason,
                null, List.of(), List.of(), null, null, null, List.of(), 0, 0, 0, null);
    }
}

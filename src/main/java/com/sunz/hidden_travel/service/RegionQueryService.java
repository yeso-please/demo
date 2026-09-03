package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.CandidateItem;
import com.sunz.hidden_travel.controller.dto.CourseCard;
import com.sunz.hidden_travel.controller.dto.CourseInitItem;
import com.sunz.hidden_travel.controller.dto.CoursePageData;
import com.sunz.hidden_travel.controller.dto.CoursePoint;
import com.sunz.hidden_travel.controller.dto.GoodPriceShop;
import com.sunz.hidden_travel.controller.dto.RegionBundle;
import com.sunz.hidden_travel.controller.dto.RegionPreview;
import com.sunz.hidden_travel.domain.Attraction;
import com.sunz.hidden_travel.domain.FoodPlace;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.Specialty;
import com.sunz.hidden_travel.domain.TravelCourse;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.repository.FoodPlaceRepository;
import com.sunz.hidden_travel.repository.GoodPriceShopRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.SpecialtyRepository;
import com.sunz.hidden_travel.repository.TravelCourseRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/**
 * SIG_CD 기준으로 DB 실데이터를 조립해 지역 화면(패널/상세)에 제공한다.
 * (DummyRegionData 를 대체하는 실데이터 조회 서비스)
 */
@Service
public class RegionQueryService {

    private static final int SHOP_LIMIT = 6;
    private static final int COURSE_CARD_LIMIT = 3;
    private static final int CANDIDATE_LIMIT = 40;

    /* 착한가격업소의 비식당 업종 제외 기준은 GoodPriceCategories 로 옮겼다(여러 곳에서 공용) */

    /** TourAPI 여행코스 cat3 코드 → 한글 라벨 */
    private static final Map<String, String> COURSE_THEME = Map.ofEntries(
            Map.entry("C0112", "가족 코스"), Map.entry("C0113", "나홀로 코스"),
            Map.entry("C0114", "힐링 코스"), Map.entry("C0115", "캠핑 코스"),
            Map.entry("C0116", "맛 코스"), Map.entry("C0117", "1박 2일 코스"),
            Map.entry("C0118", "낭만 코스"), Map.entry("C0119", "인생샷 코스"),
            Map.entry("C0120", "등산 코스"), Map.entry("C0121", "트레킹 코스")
    );

    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final FoodPlaceRepository foodPlaceRepository;
    private final GoodPriceShopRepository goodPriceShopRepository;
    private final SpecialtyRepository specialtyRepository;
    private final TravelCourseRepository travelCourseRepository;
    private final DayPlanService dayPlanService;

    public RegionQueryService(RegionRepository regionRepository,
                              AttractionRepository attractionRepository,
                              FoodPlaceRepository foodPlaceRepository,
                              GoodPriceShopRepository goodPriceShopRepository,
                              SpecialtyRepository specialtyRepository,
                              TravelCourseRepository travelCourseRepository,
                              DayPlanService dayPlanService) {
        this.regionRepository = regionRepository;
        this.attractionRepository = attractionRepository;
        this.foodPlaceRepository = foodPlaceRepository;
        this.goodPriceShopRepository = goodPriceShopRepository;
        this.specialtyRepository = specialtyRepository;
        this.travelCourseRepository = travelCourseRepository;
        this.dayPlanService = dayPlanService;
    }

    public RegionBundle bundle(String sigCd) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        String name = region != null ? region.getName() : "알 수 없는 지역";
        String province = region != null ? region.getProvince() : "";

        List<Attraction> attractions = attractionRepository.findBySigCd(sigCd);
        List<FoodPlace> foods = foodPlaceRepository.findBySigCd(sigCd);
        List<com.sunz.hidden_travel.domain.GoodPriceShop> shops = goodPriceShopRepository.findBySigCd(sigCd);
        List<Specialty> specialties = specialtyRepository.findBySigCd(sigCd);
        List<TravelCourse> courses = travelCourseRepository.findBySigCd(sigCd);

        int attractionCount = attractions.size();
        int foodCount = foods.size();
        int shopCount = shops.size();
        int specialtyCount = specialties.size();
        boolean dataReady = attractionCount + foodCount + shopCount + specialtyCount > 0;

        String aiSummary = dataReady
                ? String.format("%s %s · 관광지 %d곳, 착한가격업소 %d곳, 특산물 %d종이 기다리는 곳입니다.",
                        province, name, attractionCount, shopCount, specialtyCount)
                : "이 지역의 여행 정보는 아직 준비 중이에요. 조금만 기다려 주세요.";

        List<String> specialtyNames = specialties.stream().map(Specialty::getName).toList();

        // 식당 위주로 노출(식당이 없으면 전체로 폴백)
        List<com.sunz.hidden_travel.domain.GoodPriceShop> foodShops = shops.stream()
                .filter(s -> isFood(s.getCategory()))
                .toList();
        List<com.sunz.hidden_travel.domain.GoodPriceShop> displayShops = foodShops.isEmpty() ? shops : foodShops;
        List<GoodPriceShop> shopDtos = displayShops.stream()
                .limit(SHOP_LIMIT)
                .map(s -> new GoodPriceShop(s.getName(), s.getMenu(), priceText(s.getPrice()),
                        s.getCategory(), s.getAddr()))
                .toList();

        return new RegionBundle(sigCd, name, province, dataReady, aiSummary,
                specialtyNames, shopDtos, briefCourse(courses, attractions, foods),
                recommendedCourses(name, courses, attractions, foods),
                attractionCount, foodCount, shopCount, specialtyCount,
                region != null ? region.getLat() : null,
                region != null ? region.getLng() : null,
                // 이미 읽어둔 목록으로 판정한다 — 추가 조회 없음
                dayPlanService.canBuild(attractions, foods, shops));
    }

    /** 지도 선택 직후 다이어리 위에 펼칠 최소 지역 정보(추가 TourAPI 호출 없음). */
    public RegionPreview preview(String sigCd) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        List<Attraction> attractions = attractionRepository.findBySigCd(sigCd).stream()
                .sorted(Comparator
                        .comparing((Attraction a) -> hasText(a.getImage()) ? 0 : 1)
                        .thenComparing(a -> hasText(a.getDescription()) ? 0 : 1)
                        .thenComparing(Attraction::getName, Comparator.nullsLast(String::compareTo)))
                .limit(2)
                .toList();

        String name = region != null ? region.getName() : "알 수 없는 지역";
        String province = region != null ? region.getProvince() : "";
        String summary = attractions.stream()
                .map(Attraction::getDescription)
                .filter(this::hasText)
                .map(this::shorten)
                .findFirst()
                .orElse("이곳에서 보낸 시간을 한 줄씩 적어보세요.");

        return new RegionPreview(sigCd, name, province, summary,
                attractions.stream()
                        .map(a -> new RegionPreview.AttractionPreview(
                                a.getName(), a.getType(), shorten(a.getDescription()), a.getImage()))
                        .toList());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String shorten(String value) {
        if (!hasText(value)) return "";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() > 92 ? clean.substring(0, 92) + "…" : clean;
    }

    /* 패널 "추천 반일 코스": 여행코스 경유지 우선, 없으면 관광지+맛집 간이 조합 */
    private List<CoursePoint> briefCourse(List<TravelCourse> courses, List<Attraction> attractions, List<FoodPlace> foods) {
        List<CoursePoint> brief = new ArrayList<>();
        TravelCourse withPoints = courses.stream().filter(c -> !c.getPoints().isEmpty()).findFirst().orElse(null);
        if (withPoints != null) {
            int order = 1;
            for (com.sunz.hidden_travel.domain.CoursePoint p : withPoints.getPoints()) {
                brief.add(new CoursePoint(order++, p.getName(),
                        p.getType() != null ? p.getType() : "코스", p.getDescription(), null));
                if (order > 4) break;
            }
            return brief;
        }
        int order = 1;
        for (Attraction a : attractions) {
            brief.add(new CoursePoint(order++, a.getName(), "관광지", a.getAddr(), null));
            if (order > 3) break;
        }
        if (!foods.isEmpty()) {
            brief.add(new CoursePoint(order, foods.get(0).getName(), "맛집", foods.get(0).getAddr(), null));
        }
        return brief;
    }

    /* 상세 "추천 코스" 카드: TravelCourse 우선, 없으면 간이 코스 1개 */
    private List<CourseCard> recommendedCourses(String name, List<TravelCourse> courses,
                                                List<Attraction> attractions, List<FoodPlace> foods) {
        List<CourseCard> cards = new ArrayList<>();
        for (TravelCourse tc : courses) {
            if (cards.size() >= COURSE_CARD_LIMIT) break;
            List<String> points = tc.getPoints().stream()
                    .map(com.sunz.hidden_travel.domain.CoursePoint::getName)
                    .limit(6).toList();
            cards.add(new CourseCard(tc.getTitle(), themeLabel(tc.getTheme()), "여행코스",
                    points, tc.getTotalDistance(), tc.getId()));
        }
        if (cards.isEmpty() && (!attractions.isEmpty() || !foods.isEmpty())) {
            List<String> points = new ArrayList<>();
            attractions.stream().limit(3).forEach(a -> points.add(a.getName()));
            foods.stream().limit(1).forEach(f -> points.add(f.getName()));
            cards.add(new CourseCard(name + " 하루 한 바퀴", "추천 코스", "간이 코스", points, null, null));
        }
        return cards;
    }

    /* =========================================================
       코스 만들기 화면 데이터 (후보 4탭 + 초기 코스)
       ========================================================= */
    public CoursePageData coursePageData(String sigCd, Long courseId) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        String regionName = region != null ? region.getName() : "지역";

        List<CandidateItem> attractions = attractionRepository.findBySigCd(sigCd).stream()
                .limit(CANDIDATE_LIMIT)
                // 이미지는 목록 API 에서 이미 받아 저장해둔 값(추가 호출 없음)
                .map(a -> new CandidateItem(String.valueOf(a.getId()), "attraction", a.getName(),
                        a.getAddr(), a.getType() != null ? a.getType() : "관광지", false, null, a.getImage(),
                        a.getLat(), a.getLng()))
                .toList();

        List<CandidateItem> foods = foodPlaceRepository.findBySigCd(sigCd).stream()
                .limit(CANDIDATE_LIMIT)
                // DB 에는 cat3 코드가 그대로 들어 있다 — 라벨로 옮기지 않으면 카드에 "A05020100" 이 뜬다
                .map(f -> new CandidateItem(String.valueOf(f.getId()), "food", f.getName(),
                        f.getAddr(), FoodCategories.label(f.getCategory()), false, null, null,
                        f.getLat(), f.getLng()))
                .toList();

        List<CandidateItem> goodShops = goodPriceShopRepository.findBySigCd(sigCd).stream()
                .filter(s -> isFood(s.getCategory()))
                .limit(CANDIDATE_LIMIT)
                .map(s -> new CandidateItem(String.valueOf(s.getId()), "goodprice", s.getName(),
                        s.getAddr(), s.getCategory(), true, shopPriceText(s), null,
                        s.getLat(), s.getLng()))
                .toList();

        // 특산물은 장소가 아니라 좌표가 없다 — 지도에는 찍히지 않는다
        List<CandidateItem> specialties = specialtyRepository.findBySigCd(sigCd).stream()
                .map(sp -> new CandidateItem(String.valueOf(sp.getId()), "specialty", sp.getName(),
                        sp.getSeason(), "특산물", false, null, null, null, null))
                .toList();

        com.sunz.hidden_travel.domain.TravelCourse selectedCourse = courseId == null ? null
                : travelCourseRepository.findById(courseId).orElse(null);
        List<CourseInitItem> initial = new ArrayList<>();
        if (selectedCourse != null) {
                int order = 1;
                for (com.sunz.hidden_travel.domain.CoursePoint p : selectedCourse.getPoints()) {
                    // 경유지의 contentId 로 이미 적재된 관광지를 찾으면 그 id 를 넘겨
                    // 후보 카드에서 담은 항목과 똑같이 동작하게 한다.
                    Attraction matched = (p.getContentId() == null || p.getContentId().isBlank())
                            ? null
                            : attractionRepository.findFirstBySourceContentId(p.getContentId()).orElse(null);

                    initial.add(new CourseInitItem(
                            order++,
                            null,   // 추천 코스 경유지에는 시간대가 없다(하루 코스만 채운다)
                            p.getName(),
                            p.getType() != null ? p.getType() : "코스",
                            "course",
                            false,
                            matched != null ? matched.getId() : null,
                            p.getContentId(),
                            matched != null && matched.getImage() != null ? matched.getImage() : p.getImage(),
                            matched != null ? matched.getAddr() : null,
                            matched != null ? matched.getLat() : null,
                            matched != null ? matched.getLng() : null,
                            matched != null ? matched.getDescription() : null));
                }
        }

        String selectedTitle = selectedCourse != null ? selectedCourse.getTitle() : null;
        String selectedDescription = selectedCourse != null ? selectedCourse.getDescription() : null;
        String selectedImage = selectedCourse == null ? null : selectedCourse.getPoints().stream()
                .map(com.sunz.hidden_travel.domain.CoursePoint::getImage)
                .filter(this::hasText)
                .findFirst().orElse(null);
        boolean overviewPending = selectedCourse != null && !hasText(selectedDescription);
        String recommendationReason = selectedCourse == null ? null
                : selectedCourse.getPoints().isEmpty()
                ? regionName + "에 등록된 공식 여행 코스라서 이 지역을 둘러보기 좋은 출발점이에요."
                : regionName + "에서 함께 둘러보기 좋은 " + selectedCourse.getPoints().size()
                + "곳의 경유지를 하나의 여행 흐름으로 이어둔 공식 코스예요.";

        return new CoursePageData(sigCd, regionName,
                selectedTitle != null ? selectedTitle : regionName + " 코스",
                attractions, foods, goodShops, specialties, initial,
                selectedTitle, selectedDescription, selectedImage, recommendationReason, overviewPending);
    }

    private String shopPriceText(com.sunz.hidden_travel.domain.GoodPriceShop s) {
        String m = s.getMenu() != null ? s.getMenu() : "";
        return (m + " " + priceText(s.getPrice())).trim();
    }

    private String themeLabel(String cat) {
        if (cat == null) return "여행코스";
        return COURSE_THEME.getOrDefault(cat, "여행코스");
    }

    private boolean isFood(String category) {
        return GoodPriceCategories.isFood(category);
    }

    private String priceText(Integer price) {
        return price == null ? "가격문의" : String.format("%,d원", price);
    }
}

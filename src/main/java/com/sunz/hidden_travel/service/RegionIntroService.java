package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.RegionIntro;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "이 지역은 이런 곳이에요" 블록을 적재된 실데이터로 구성한다.
 *
 * TourAPI 에는 지역 단위 소개글이 없으므로(areaCode2 는 코드·이름만),
 * 지역의 성격을 <b>가진 데이터로 서술</b>한다. 지어내지 않는다.
 */
@Service
public class RegionIntroService {

    private static final int LANDMARK_COUNT = 3;
    private static final int GOOD_PRICE_COUNT = 3;
    private static final int SPECIALTY_COUNT = 6;

    /** TourAPI 음식점 분류코드(cat3) → 한글 라벨. DB 에는 코드로 저장되어 있다. */
    private static final Map<String, String> FOOD_CATEGORY = Map.of(
            "A05020100", "한식",
            "A05020200", "서양식",
            "A05020300", "일식",
            "A05020400", "중식",
            "A05020700", "이색음식점",
            "A05020900", "카페·전통찻집"
    );

    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final FoodPlaceRepository foodPlaceRepository;
    private final GoodPriceShopRepository goodPriceShopRepository;
    private final SpecialtyRepository specialtyRepository;

    public RegionIntroService(RegionRepository regionRepository,
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

    @Transactional(readOnly = true)
    public RegionIntro intro(String sigCd) {
        Region region = regionRepository.findById(sigCd).orElse(null);
        List<Attraction> attractions = attractionRepository.findBySigCd(sigCd);
        List<FoodPlace> foods = foodPlaceRepository.findBySigCd(sigCd);
        List<GoodPriceShop> shops = goodPriceShopRepository.findBySigCd(sigCd);
        List<String> specialties = specialtyRepository.findBySigCd(sigCd).stream()
                .map(Specialty::getName)
                .filter(n -> n != null && !n.isBlank())
                .limit(SPECIALTY_COUNT)
                .toList();

        return new RegionIntro(
                overview(region, attractions, foods, shops),
                landmarks(attractions),
                foodSummary(foods),
                goodPriceHighlights(shops),
                specialties);
    }

    /* ---------- 개요 ---------- */
    private String overview(Region region, List<Attraction> attractions,
                            List<FoodPlace> foods, List<GoodPriceShop> shops) {
        if (region == null) {
            return "";
        }
        // 숫자는 바로 위 지표에서 이미 보인다. 여기서는 실제 장소를 한 편의 흐름으로 읽게 한다.
        List<String> names = attractions.stream()
                .map(Attraction::getName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .limit(3)
                .toList();
        if (names.isEmpty()) {
            return region.getName() + "의 여행 장면을 채울 장소를 준비하고 있어요.";
        }
        StringBuilder story = new StringBuilder();
        story.append(region.getName()).append("의 여행은 ")
                .append(String.join(", ", names)).append(" 같은 장소에서 시작됩니다. ")
                .append("각 장소의 이야기를 읽고 마음에 드는 장면을 골라, 당신의 속도에 맞는 여행 순서로 이어보세요.");
        if (!foods.isEmpty() || !shops.isEmpty()) {
            story.append(" 이동 사이에는 아래의 지역 먹거리도 함께 살펴볼 수 있어요.");
        }
        return story.toString();
    }

    /* ---------- 대표 랜드마크 ---------- */
    private List<RegionIntro.Landmark> landmarks(List<Attraction> attractions) {
        // 이미 상세를 받아둔 곳(설명 보유)과 사진 있는 곳을 앞세운다.
        // 설명이 없으면 화면이 /api/attraction/{id} 로 채운다.
        List<Attraction> sorted = new ArrayList<>(attractions);
        sorted.sort(Comparator
                .comparing((Attraction a) -> a.getDescription() != null && !a.getDescription().isBlank() ? 0 : 1)
                .thenComparing(a -> a.getImage() != null && !a.getImage().isBlank() ? 0 : 1));

        List<RegionIntro.Landmark> out = new ArrayList<>();
        for (Attraction a : sorted) {
            if (out.size() >= LANDMARK_COUNT) {
                break;
            }
            if (a.getName() == null || a.getName().isBlank()) {
                continue;
            }
            out.add(new RegionIntro.Landmark(a.getId(), a.getName(), a.getAddr(), a.getImage(), a.getDescription()));
        }
        return out;
    }

    /* ---------- 먹거리 ---------- */
    private String foodSummary(List<FoodPlace> foods) {
        if (foods.isEmpty()) {
            return "";
        }
        // 업종 분포로 이 지역 먹거리의 결을 보여준다
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (FoodPlace f : foods) {
            String label = FOOD_CATEGORY.get(f.getCategory());
            if (label != null) {
                byCategory.merge(label, 1, Integer::sum);
            }
        }
        if (byCategory.isEmpty()) {
            return "등록된 음식점 " + foods.size() + "곳이 있습니다.";
        }
        String breakdown = byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey() + " " + e.getValue() + "곳")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "음식점 " + foods.size() + "곳이 등록되어 있고, " + breakdown + "이 많습니다.";
    }

    /**
     * 착한가격업소는 메뉴·가격이 그대로 있어 가장 구체적인 먹거리 정보다.
     * 다만 미용실·세탁소 같은 서비스 업종이 섞여 있어 식당만 골라낸다
     * (거르지 않으면 먹거리 자리에 "커트 4,000원 · ○○미용실"이 뜬다).
     */
    private List<String> goodPriceHighlights(List<GoodPriceShop> shops) {
        List<String> out = new ArrayList<>();
        for (GoodPriceShop s : shops) {
            if (out.size() >= GOOD_PRICE_COUNT) {
                break;
            }
            if (!GoodPriceCategories.isFood(s.getCategory())) {
                continue;
            }
            if (s.getMenu() == null || s.getMenu().isBlank()) {
                continue;
            }
            StringBuilder line = new StringBuilder(s.getMenu().trim());
            if (s.getPrice() != null && s.getPrice() > 0) {
                line.append(' ').append(String.format("%,d", s.getPrice())).append('원');
            }
            if (s.getName() != null && !s.getName().isBlank()) {
                line.append(" · ").append(s.getName().trim());
            }
            out.add(line.toString());
        }
        return out;
    }
}

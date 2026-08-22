package com.sunz.hidden_travel.service;

import java.util.Map;

/**
 * TourAPI 음식점 분류코드(cat3) → 한글 라벨.
 * DB 에는 코드가 그대로 저장되어 있어 화면에 쓰려면 옮겨야 한다
 * (거르지 않으면 카드에 "A05020100" 이 그대로 노출된다).
 *
 * <p>착한가격업소 업종 판정({@link GoodPriceCategories})과 같은 자리의 도구다.
 */
public final class FoodCategories {

    private static final Map<String, String> LABELS = Map.of(
            "A05020100", "한식",
            "A05020200", "서양식",
            "A05020300", "일식",
            "A05020400", "중식",
            "A05020700", "이색음식점",
            "A05020900", "카페·전통찻집"
    );

    /** 카페·전통찻집 — 끼니 자리에 넣으면 어색해서 따로 가려낸다 */
    private static final String CAFE = "A05020900";

    private FoodCategories() {
    }

    public static boolean isCafe(String cat3) {
        return CAFE.equals(cat3);
    }

    /** 코드에 대응하는 라벨. 모르는 코드거나 비어 있으면 "먹거리" */
    public static String label(String cat3) {
        if (cat3 == null || cat3.isBlank()) {
            return "먹거리";
        }
        return LABELS.getOrDefault(cat3, "먹거리");
    }
}

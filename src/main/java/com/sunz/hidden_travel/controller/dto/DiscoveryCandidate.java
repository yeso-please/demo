package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * 발견 후보 공식 코스. 행정구역은 지도를 밝히고 위치를 설명하는 메타데이터일 뿐,
 * 사용자가 선택하고 여는 추천 단위는 {@code courseId}다.
 *
 * <b>점수를 담지 않는다.</b> 유사도는 후보를 고르는 데만 쓰고 화면에는 내보내지 않는다 —
 * 점수를 보여주면 '랭킹 없는 발견'이 이름만 바뀐 랭킹이 된다.
 *
 * <p>{@code courseImage} 는 없을 수 있다(경유지 사진이 적재되지 않은 코스). 화면은 빈 회색 띠를
 * 두지 말고 사진 영역 자체를 빼야 한다 — 이름만 있는 카드가 깨진 카드보다 낫다.
 */
public record DiscoveryCandidate(
        String sigCd,
        String name,
        String province,
        List<String> tags,
        List<String> matchedTags,
        String reason,
        String image,
        String heroName,
        int attractionCount,
        int foodCount,
        Double lat,
        Double lng,
        Long courseId,
        String courseTitle,
        String courseSubtitle,
        String courseImage,
        List<String> courseStops) {
}

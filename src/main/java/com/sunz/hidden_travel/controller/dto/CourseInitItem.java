package com.sunz.hidden_travel.controller.dto;

/**
 * 코스 만들기 오른쪽 타임라인 초기 항목.
 * 추천 코스에서 담아온 경유지이거나, '이 지역에서 하루 보내기'가 자동 조립한 자리다.
 *
 * 경유지도 TourAPI 독립 콘텐츠라서 관광지와 동일하게 사진·상세를 제공한다.
 * - attractionId: 이미 적재된 관광지와 매칭되면 그 id (→ /api/attraction/{id})
 * - contentId   : 매칭되지 않았을 때 쓰는 TourAPI contentId
 *                 (→ /api/attraction/by-content/{contentId}, 조회 시 관광지로 저장)
 *
 * - slot        : 오전·점심·오후·저녁. 하루 코스에서만 채워지고 그 외에는 null
 * - type        : 화면에 보이는 분류 라벨(관광지 · 한식 · 착한가격업소 …)
 * - dataType    : course.js 가 쓰는 값(attraction/food/goodprice/course).
 *                 저장 시 착한가격업소 수 집계가 이 값으로 이뤄져 라벨과 분리해 둔다.
 */
public record CourseInitItem(
        int order,
        String slot,
        String name,
        String type,
        String dataType,
        boolean sage,
        Long attractionId,
        String contentId,
        String image,
        String addr,
        Double lat,
        Double lng
) {}

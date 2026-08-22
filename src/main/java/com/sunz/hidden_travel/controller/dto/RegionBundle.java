package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * 지역 화면(패널/상세) 응답 번들. SIG_CD 기준으로 DB 실데이터를 조립한다.
 * - dataReady: 적재된 데이터가 하나라도 있는지(빈 상태 UI 판정용)
 * - shops/briefCourse 는 표시용으로 개수 제한, *_Count 는 실제 전체 개수(지표 스트립용)
 * - dayPlanAvailable: '이 지역에서 하루 보내기'로 코스를 자동 조립할 수 있는지.
 *   눌러도 아무것도 안 나오는 버튼을 내보내지 않기 위해 미리 판정한다.
 */
public record RegionBundle(
        String sigCd,
        String name,
        String province,
        boolean dataReady,
        String aiSummary,
        List<String> specialties,
        List<GoodPriceShop> shops,
        List<CoursePoint> briefCourse,
        List<CourseCard> recommendedCourses,
        int attractionCount,
        int foodCount,
        int shopCount,
        int specialtyCount,
        Double lat,
        Double lng,
        boolean dayPlanAvailable
) {}

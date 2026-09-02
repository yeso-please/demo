package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/** 순위가 아닌 서로 다른 발견 이유로 제시하는 실행 가능한 코스 후보. */
public record TripCandidate(
        String courseKey,
        Long officialCourseId,
        String direction,
        String directionLabel,
        String sigCd,
        String regionName,
        String province,
        String narrativeTitle,
        String image,
        List<String> tags,
        String reason,
        String newPoint,
        String duration,
        int stopCount,
        String distanceText,
        String courseOrigin,
        String warning,
        int goodPriceCount,
        Double lat,
        Double lng,
        String detailUrl,
        DayPlan dayPlan
) {}

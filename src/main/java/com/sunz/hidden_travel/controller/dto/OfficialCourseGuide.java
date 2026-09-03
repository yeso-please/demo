package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * TourAPI 공식 코스를 실제 여행 후보로 판단하는 데 필요한 요약 정보.
 * 수치가 없는 항목을 그럴듯하게 만들지 않고, 현재 적재된 데이터의 범위도 함께 보여준다.
 */
public record OfficialCourseGuide(
        List<String> images,
        int stopCount,
        int mappedStopCount,
        int detailedStopCount,
        int hoursKnownCount,
        String theme,
        String sourceDistance
) {
    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    public boolean hasDataGaps() {
        return mappedStopCount < stopCount || hoursKnownCount < stopCount;
    }
}

package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * TourAPI 공식 코스를 실제 여행 후보로 판단하는 데 필요한 요약 정보.
 * 수치가 없는 항목을 그럴듯하게 만들지 않고, 현재 적재된 데이터의 범위도 함께 보여준다.
 */
public record OfficialCourseGuide(
        List<CoursePhoto> photos,
        int stopCount,
        int mappedStopCount,
        int detailedStopCount,
        int hoursKnownCount,
        String theme,
        String sourceDistance
) {
    public boolean hasImages() {
        return photos != null && !photos.isEmpty();
    }

    public boolean hasDataGaps() {
        return mappedStopCount < stopCount || hoursKnownCount < stopCount;
    }

    /** 경유지 순서와 사진을 함께 보존해 사진도 여행의 흐름으로 읽히게 한다. */
    public record CoursePhoto(String image, String stopName, int order) {}
}

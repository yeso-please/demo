package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * 코스 만들기 화면 모델. 왼쪽 후보 4개 탭 + 오른쪽 초기 코스.
 */
public record CoursePageData(
        String sigCd,
        String regionName,
        String courseName,
        List<CandidateItem> attractions,
        List<CandidateItem> foods,
        List<CandidateItem> goodShops,
        List<CandidateItem> specialties,
        List<CourseInitItem> initialItems,
        String recommendedCourseTitle,
        String recommendedCourseDescription,
        String recommendedCourseImage,
        String recommendationReason,
        boolean overviewPending,
        OfficialCourseGuide officialCourseGuide
) {

    /**
     * 자동 조립한 하루 코스를 초기 타임라인으로 얹은 사본.
     * 왼쪽 후보 목록은 그대로 두어 사용자가 곧바로 고칠 수 있게 한다
     * (자동 코스는 출발점이지 결론이 아니다).
     */
    public CoursePageData withInitialCourse(String name, List<CourseInitItem> items) {
        return new CoursePageData(sigCd, regionName, name, attractions, foods, goodShops, specialties, items,
                recommendedCourseTitle, recommendedCourseDescription, recommendedCourseImage,
                recommendationReason, overviewPending, officialCourseGuide);
    }
}

package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/** 다이어리 한 편의 상세. */
public record DiaryDetail(
        Long diaryId,
        String nickname,
        String sigCd,
        String regionLabel,
        String courseTitle,
        List<String> courseStops,
        String whenText,
        String satisfactionLabel,
        List<String> photoPaths,
        String text,
        List<String> tags,
        String createdAt,
        boolean shared,
        boolean mine
) {
    public boolean fromCourse() {
        return courseTitle != null && !courseTitle.isBlank();
    }
}

package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * 다이어리 피드(/reviews)의 카드 1건.
 *
 * <p><b>제목이 지역이다.</b> 예전 후기 카드는 코스 이름을 제목으로 썼는데, 다이어리는
 * 대부분 코스 없이 쓴 '기억'이라 코스 이름이 없다. 그리고 사람들이 기억하는 단위는
 * 코스명이 아니라 지역이다.
 *
 * <p>{@code courseTitle} 이 있으면 우리 추천으로 실제 다녀와서 쓴 '기록'이다 —
 * 카드에서 그 사실을 드러낸다. 같은 피드 안에서 무게가 다른 글이다.
 */
public record DiaryCard(
        Long diaryId,
        String nickname,
        String sigCd,
        String regionLabel,
        /** 코스로 다녀온 편이면 코스 이름, 아니면 null */
        String courseTitle,
        String whenText,
        String coverPhoto,
        int photoCount,
        String excerpt,
        List<String> tags,
        String createdAt
) {
    public boolean fromCourse() {
        return courseTitle != null && !courseTitle.isBlank();
    }

    public boolean hasPhoto() {
        return coverPhoto != null && !coverPhoto.isBlank();
    }
}

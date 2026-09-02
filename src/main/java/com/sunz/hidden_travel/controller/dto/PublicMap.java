package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * 남에게 보여주는 '내 지도'.
 *
 * <p>공유 단위가 코스가 아니라 지도인 이유 — 코스를 공유하면 그건 정보지만,
 * 지도를 공유하면 그건 사람이다. 어두운 전국 위에 켜진 불의 모양만 봐도
 * "이 사람은 바닷가만 다녔네"가 읽히고, 그게 글을 읽게 만드는 입구가 된다.
 *
 * <p><b>공개한 편만 담는다.</b> 비공개로 쓴 편은 지도의 불도 켜지 않는다 —
 * 글은 감췄는데 다녀온 사실이 드러나면 감춘 게 아니다.
 */
public record PublicMap(
        String nickname,
        int litCount,
        int totalRegions,
        /** 켜진 지역들 */
        List<Lit> lit,
        /** 이 사람의 여행을 관통하는 결 — 많이 쓴 순서로 최대 5개 */
        List<String> topTags,
        /** 공개된 편 */
        List<DiaryCard> entries
) {
    public boolean isEmpty() {
        return lit.isEmpty();
    }

    /** @param sigCd 시군구 코드 · @param name 지역명 · @param diaryId 눌렀을 때 열 글 */
    public record Lit(String sigCd, String name, Long diaryId) {}
}

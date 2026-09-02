package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * 여행 다이어리 한 편.
 *
 * <p>설문이 아니라 <b>기록</b>이다. 지역을 고르고 줄글로 적으면 그 글에서 경험을 읽는다.
 * 태그를 직접 고르는 것보다 문단 하나가 신호가 훨씬 많고, 사용자에게도 덜 시킨다.
 *
 * @param sigCd        시군구 코드
 * @param satisfaction again(또 가고 싶다) / good(좋았다) / soso(그저 그랬다) / bad(나와 안 맞았다)
 * @param note         사용자가 쓴 줄글. 여기서 경험 태그를 읽는다.
 * @param tags         읽힌 태그 중 사용자가 남겨둔 것 — 틀린 것을 뺄 수 있어야 하므로 따로 받는다.
 *                     비어 있으면 note 에서 다시 읽는다.
 * @param when         "2024년 가을" 같은 자유 표기. 표시용이며 계산에 쓰지 않는다.
 */
public record VisitInput(String sigCd, String satisfaction, String note, List<String> tags, String when) {

    public VisitInput {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean hasNote() {
        return note != null && !note.isBlank();
    }
}

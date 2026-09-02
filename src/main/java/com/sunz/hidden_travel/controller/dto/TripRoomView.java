package com.sunz.hidden_travel.controller.dto;

import com.sunz.hidden_travel.domain.SharedTripRoom;

import java.util.List;

public record TripRoomView(
        SharedTripRoom room,
        TripDiscoveryRequest request,
        List<CandidateReactionView> candidates,
        List<String> participants,
        TripCandidate selectedCandidate
) {
    /**
     * 방장이 여행 조건을 실제로 밝힌 방인지.
     *
     * <p>{@link TripDiscoveryRequest} 는 빈 값을 기본값으로 채우므로(서울·당일·자동차)
     * request 만 봐서는 "말한 적 없음"과 "서울이라고 말함"을 구분할 수 없다.
     * 저장된 원본을 봐야 한다 — 화면은 이 값이 false 면 조건 줄을 아예 그리지 않는다.
     */
    public boolean hasContext() {
        return room.getDeparture() != null && !room.getDeparture().isBlank();
    }

    public int reactionCount() {
        return candidates.stream().mapToInt(v -> v.reactions().size()).sum();
    }
}

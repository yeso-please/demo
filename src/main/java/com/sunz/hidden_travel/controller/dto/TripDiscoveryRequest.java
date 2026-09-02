package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/** 사용자가 지도 위 추천 패널에서 입력한 짧은 여행 문맥. */
public record TripDiscoveryRequest(
        String departure,
        String duration,
        String companion,
        int people,
        String transport,
        List<String> experiences,
        String freeText
) {
    public TripDiscoveryRequest {
        departure = textOr(departure, "서울");
        duration = textOr(duration, "당일");
        companion = textOr(companion, "친구");
        people = Math.max(1, Math.min(10, people));
        transport = textOr(transport, "자동차");
        experiences = experiences == null ? List.of() : experiences.stream()
                .filter(v -> v != null && !v.isBlank()).distinct().limit(6).toList();
        freeText = freeText == null ? "" : freeText.trim();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

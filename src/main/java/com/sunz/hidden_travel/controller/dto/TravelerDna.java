package com.sunz.hidden_travel.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 여행 DNA — 사용자의 경험 취향을 연속 벡터로 들고, 화면에는 문장·막대로만 보여준다.
 *
 * MBTI 처럼 고정 유형으로 분류하지 않는다(PRD 8.3).
 * 막대는 <b>사용자 내부 차원 비교</b>이지 지역 간 순위가 아니다.
 *
 * @param axes     상위 축 (표시용)
 * @param sentence 한 줄 요약
 * @param basis    이 판단의 근거 문장 — "무엇 때문에 이렇게 나왔는지"를 숨기지 않는다
 * @param vector   전체 벡터 (내부 계산용, 화면에 숫자로 노출하지 않는다)
 */
public record TravelerDna(List<Axis> axes, String sentence, List<String> basis, Map<String, Double> vector) {

    public record Axis(String tag, double weight) {
    }

    public boolean isEmpty() {
        return axes == null || axes.isEmpty();
    }
}

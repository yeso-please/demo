package com.sunz.hidden_travel.controller.dto;

/**
 * 관광지 상세(카드 펼치기) 응답.
 *
 * @param pending true 면 상세를 아직 못 가져온 상태(호출 한도 소진 · 응답 실패).
 *                이 경우 detailFetched 를 찍지 않으므로 다음에 다시 시도한다.
 *                화면은 이름·주소·이미지만 보여주고 안내 문구를 띄운다.
 */
public record AttractionDetail(
        Long id,
        String name,
        String addr,
        String image,
        String overview,
        String homepage,
        String usetime,
        String restdate,
        String parking,
        String infocenter,
        String tel,
        boolean pending
) {}

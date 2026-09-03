package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/** 지도에서 지역을 고른 직후 다이어리 상단에 펼치는 작은 지역 미리보기. */
public record RegionPreview(
        String sigCd,
        String name,
        String province,
        String summary,
        List<AttractionPreview> attractions
) {
    public record AttractionPreview(String name, String type, String description, String image) {}
}

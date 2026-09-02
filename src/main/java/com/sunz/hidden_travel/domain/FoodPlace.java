package com.sunz.hidden_travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 음식·먹거리 장소.
 */
@Entity
@Table(name = "food_place", indexes = @Index(name = "idx_food_sig", columnList = "sig_cd"))
@Getter
@Setter
@NoArgsConstructor
public class FoodPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sig_cd", length = 5, nullable = false)
    private String sigCd;

    private String name;

    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String addr;

    private Double lat;

    private Double lng;

    /** TourAPI contentId (중복 적재 방지 키) */
    private String sourceContentId;

    /**
     * 대표 사진. areaBasedList 응답의 firstimage 로 채운다(추가 호출 없음).
     * 코스 카드의 한 자리가 식당이라, 여기가 비면 카드 세 장 중 한 장이 늘 빈다.
     */
    private String image;

    /**
     * 영업시간 원문. <b>목록 API 에는 없고 detailIntro(음식점) 의 opentimefood 에만 있다</b> —
     * 1건당 호출 1회가 들어 별도 배치로만 채운다.
     */
    @Column(columnDefinition = "TEXT")
    private String usetime;

    /** 상세(영업시간)를 이미 조회했는지 — 배치가 같은 행을 반복해 예산을 태우지 않도록 */
    @Column(name = "detail_fetched", nullable = false, columnDefinition = "boolean default false")
    private boolean detailFetched = false;
}

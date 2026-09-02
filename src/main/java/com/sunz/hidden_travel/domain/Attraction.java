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
 * 관광지 (TourAPI 콘텐츠).
 */
@Entity
@Table(name = "attraction", indexes = @Index(name = "idx_attraction_sig", columnList = "sig_cd"))
@Getter
@Setter
@NoArgsConstructor
public class Attraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sig_cd", length = 5, nullable = false)
    private String sigCd;

    private String name;

    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String addr;

    private Double lat;

    private Double lng;

    /** TourAPI contentId */
    private String sourceContentId;

    private String image;

    /* =========================================================
       상세 정보 — 목록 API 에는 없고 detailCommon2/detailIntro2 로만 얻는다.
       콘텐츠 1건당 호출 2회가 들어 전량 적재는 비현실적이므로,
       사용자가 실제로 펼쳐본 관광지만 채워 넣고 여기에 캐시한다.
       (description = detailCommon2 의 overview)
       ========================================================= */

    /** 공식 홈페이지 */
    @Column(columnDefinition = "TEXT")
    private String homepage;

    /** 이용시간 */
    @Column(columnDefinition = "TEXT")
    private String usetime;

    /** 휴무일 */
    private String restdate;

    /** 주차 가능 여부 */
    @Column(columnDefinition = "TEXT")
    private String parking;

    /** 문의·안내처 */
    private String infocenter;

    /** 전화번호 (목록 API 에 포함되어 있어 적재 시 함께 저장) */
    private String tel;

    /**
     * 상세를 이미 조회했는지. 조회 결과가 비어 있어도 true 로 두어
     * 같은 콘텐츠를 반복 호출하지 않는다(호출 한도 절약).
     *
     * default false 를 명시해야 이미 적재된 행이 있는 테이블에도 컬럼을 추가할 수 있다
     * (기본값이 없으면 NOT NULL 제약 때문에 ALTER 가 실패한다).
     */
    @Column(name = "detail_fetched", nullable = false, columnDefinition = "boolean default false")
    private boolean detailFetched = false;

    /**
     * 축제 시작일 (yyyyMMdd). type="축제" 일 때만 채워진다.
     * TourAPI 가 문자열로 주므로 원문 그대로 보관한다(형식 오류가 섞여도 적재가 실패하지 않게).
     */
    @Column(name = "event_start_date", length = 8)
    private String eventStartDate;

    /** 축제 종료일 (yyyyMMdd). type="축제" 일 때만 채워진다. */
    @Column(name = "event_end_date", length = 8)
    private String eventEndDate;
}

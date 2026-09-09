package com.sunz.hidden_travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 하루 안의 한 자리 — 관광지이거나, 비워둔 식사·휴식 시간이거나, 메모다.
 *
 * <p>식사는 <b>시간 블록</b>으로 들어간다. 식당을 자동으로 넣지 않기 때문이다(PRD §7.2).
 * 사용자가 먹거리를 고르면 그 이름이 이 블록에 붙는다.
 */
@Entity
@Table(name = "trip_item")
@Getter
@Setter
@NoArgsConstructor
public class TripItem {

    public enum Kind {
        /** 관광지 */
        SIGHT,
        /** 식사 시간 */
        MEAL,
        /** 휴식 */
        REST,
        /** 메모만 있는 자리 */
        MEMO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_day_id", nullable = false)
    private TripDay day;

    /** 1부터. 순서를 바꾸면 다시 매긴다 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Kind kind = Kind.SIGHT;

    @Column(nullable = false)
    private String name;

    /** 화면에 붙는 분류 라벨 ("관광지" · "식사") */
    @Column(length = 40)
    private String category;

    /** 편집기가 쓰는 원본 종류 (attraction / food / goodprice) */
    @Column(name = "data_type", length = 20)
    private String dataType;

    @Column(nullable = false)
    private boolean sage;

    /** 원본 관광지 id — '자세히'와 검증에 쓴다. 지어낸 장소를 담지 않기 위한 연결고리다 */
    @Column(name = "attraction_id")
    private Long attractionId;

    @Column(length = 500)
    private String image;

    private String addr;

    private Double lat;

    private Double lng;

    /** 이 자리에 머무는 시간(분). 사용자가 고칠 수 있다 */
    @Column(name = "stay_minutes", nullable = false)
    private int stayMinutes;

    /**
     * 직전 자리에서 여기까지 이동(분).
     * <b>null 은 '아직 모른다'는 뜻이다</b> — 0으로 바꿔 합산하지 않는다(PRD F-07).
     */
    @Column(name = "leg_minutes")
    private Integer legMinutes;

    /** 운영시간을 확인하지 못한 곳 — 화면에 그대로 표시한다 */
    @Column(name = "hours_unverified", nullable = false)
    private boolean hoursUnverified;

    @Column(length = 1000)
    private String description;

    /** 사용자가 남긴 메모 */
    @Column(length = 500)
    private String note;

    public boolean hasCoord() {
        return lat != null && lng != null;
    }

    /** 첫 자리는 이동 구간 자체가 없다 — '모른다'와 구분한다 */
    public boolean legUnknown() {
        return sortOrder > 1 && legMinutes == null;
    }

    /** "50분" · "1시간 20분" */
    public String stayText() {
        return minutesText(stayMinutes);
    }

    public String legText() {
        return legMinutes == null ? null : minutesText(legMinutes);
    }

    public static String minutesText(int minutes) {
        if (minutes <= 0) {
            return "0분";
        }
        int h = minutes / 60;
        int m = minutes % 60;
        if (h > 0) {
            return m > 0 ? h + "시간 " + m + "분" : h + "시간";
        }
        return m + "분";
    }
}

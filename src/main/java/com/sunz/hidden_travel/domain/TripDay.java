package com.sunz.hidden_travel.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 여행 계획의 하루.
 *
 * <p>숙소와 먹거리는 <b>자동으로 정해주지 않는다.</b> 근처를 찾아보게 하고,
 * 사용자가 고른 것을 여기에 등록한다(PRD F-08 · §7.2).
 * 좌표가 있으면 동선에 반영하고, 없으면 반영하지 않는다 — 없는 값을 추정해 채우지 않는다.
 */
@Entity
@Table(name = "trip_day")
@Getter
@Setter
@NoArgsConstructor
public class TripDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan plan;

    /** 1부터 시작 */
    @Column(name = "day_index", nullable = false)
    private int dayIndex;

    /* ----- 숙소 (그날 밤) ----- */

    @Column(name = "stay_name")
    private String stayName;

    @Column(name = "stay_addr")
    private String stayAddr;

    /** 외부 예약 페이지 링크. 예약 완료 여부는 우리가 확인하지 않는다 */
    @Column(name = "stay_url", length = 500)
    private String stayUrl;

    @Column(name = "stay_lat")
    private Double stayLat;

    @Column(name = "stay_lng")
    private Double stayLng;

    /* ----- 먹거리 (그날 식사) ----- */

    @Column(name = "food_name")
    private String foodName;

    @Column(name = "food_addr")
    private String foodAddr;

    /** 착한가격업소 여부 — 배지로 구분한다 */
    @Column(name = "food_sage", nullable = false)
    private boolean foodSage;

    /** 착한가격업소 공시 가격 문구 ("1인 8,000원"). 그 외에는 null */
    @Column(name = "food_price_text")
    private String foodPriceText;

    @Column(name = "food_lat")
    private Double foodLat;

    @Column(name = "food_lng")
    private Double foodLng;

    @OneToMany(mappedBy = "day", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc")
    private List<TripItem> items = new ArrayList<>();

    public TripDay(int dayIndex) {
        this.dayIndex = dayIndex;
    }

    public void addItem(TripItem item) {
        item.setDay(this);
        item.setSortOrder(items.size() + 1);
        items.add(item);
    }

    /**
     * 화면에 찍는 관광지 순번(1부터). 식사·휴식 블록은 번호를 차지하지 않는다 —
     * 지도 마커 번호와 목록 번호가 같아야 하기 때문이다(PRD F-07).
     * 관광지가 아니면 null.
     */
    public Integer sightNumber(TripItem item) {
        if (item == null || item.getKind() != TripItem.Kind.SIGHT) {
            return null;
        }
        int n = 0;
        for (TripItem i : items) {
            if (i.getKind() == TripItem.Kind.SIGHT) {
                n++;
                if (i == item) {
                    return n;
                }
            }
        }
        return null;
    }

    /** 관광지가 하나라도 있는지 — 확정 조건이자 '빈 Day' 판정 */
    public boolean hasSight() {
        return items.stream().anyMatch(i -> i.getKind() == TripItem.Kind.SIGHT);
    }

    public boolean hasStay() {
        return stayName != null && !stayName.isBlank();
    }

    public boolean hasFood() {
        return foodName != null && !foodName.isBlank();
    }

    /** 숙소 좌표가 있어야 전날 종료 · 다음 날 시작 동선에 반영할 수 있다 */
    public boolean stayHasCoord() {
        return stayLat != null && stayLng != null;
    }

    /** 관광 체류 합계(분) */
    public int sightMinutes() {
        return items.stream().filter(i -> i.getKind() == TripItem.Kind.SIGHT)
                .mapToInt(TripItem::getStayMinutes).sum();
    }

    /** 식사·휴식 합계(분) */
    public int breakMinutes() {
        return items.stream().filter(i -> i.getKind() != TripItem.Kind.SIGHT)
                .mapToInt(TripItem::getStayMinutes).sum();
    }

    /** 계산된 구간만 더한 이동 합계(분). 미계산 구간은 0으로 세지 않고 따로 알린다 */
    public int moveMinutes() {
        return items.stream().map(TripItem::getLegMinutes).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
    }

    /** 이동시간을 아직 못 구한 구간 수 (PRD F-07 — 0분으로 합산하지 않는다) */
    public int unknownLegCount() {
        return (int) items.stream().filter(TripItem::legUnknown).count();
    }

    public int usedMinutes() {
        return sightMinutes() + breakMinutes() + moveMinutes();
    }

    /* 화면에 그대로 쓰는 시간 문구. 템플릿에서 정적 메서드를 부르지 않게 여기서 감싼다 */
    public String sightText()  { return TripItem.minutesText(sightMinutes()); }
    public String moveText()   { return TripItem.minutesText(moveMinutes()); }
    public String breakText()  { return TripItem.minutesText(breakMinutes()); }
    public String usedText()   { return TripItem.minutesText(usedMinutes()); }

    /** 하루 사용 시간(8시간) 대비 비율(%) — 막대 폭에 쓴다 */
    public int sightPercent()  { return percentOfDay(sightMinutes()); }
    public int movePercent()   { return percentOfDay(moveMinutes()); }
    public int breakPercent()  { return percentOfDay(breakMinutes()); }

    /** 기본 하루 예산 8시간. 사용자가 고르는 값으로 바꿀 때 이 상수만 옮기면 된다 */
    public static final int DAY_BUDGET_MINUTES = 480;

    private int percentOfDay(int minutes) {
        return Math.min(100, (int) Math.round(minutes * 100.0 / DAY_BUDGET_MINUTES));
    }

    /** 동선의 기준이 되는 마지막 장소 — 숙소·먹거리를 여기 주변에서 찾는다 */
    public TripItem lastPlace() {
        TripItem found = null;
        for (TripItem i : items) {
            if (i.hasCoord()) {
                found = i;
            }
        }
        return found;
    }
}

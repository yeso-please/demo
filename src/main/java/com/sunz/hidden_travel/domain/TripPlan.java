package com.sunz.hidden_travel.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 여러 날의 여행 계획 (PRD v4 F-06).
 *
 * <p>{@link SavedCourse}(하루 · 경유지 한 줄)를 대체하지 않고 나란히 둔다.
 * 기존 저장 코스와 후기는 그대로 살아 있어야 하기 때문이다(PRD §14).
 * 새 핵심 진입(랜덤 도착 → 이 지역으로 여행 떠나기)만 이쪽으로 온다.
 *
 * <p><b>비회원도 초안을 만들 수 있다.</b> 회원은 {@link #userId}, 비회원은 {@link #guestKey}(세션 id)로
 * 소유를 가린다. 비회원 초안은 세션이 끝나면 다시 찾을 수 없으므로 화면에서 그렇게 안내한다.
 */
@Entity
@Table(name = "trip_plan", indexes = {
        @Index(name = "idx_trip_plan_user", columnList = "user_id"),
        @Index(name = "idx_trip_plan_guest", columnList = "guest_key")
})
@Getter
@Setter
@NoArgsConstructor
public class TripPlan {

    public enum Status {
        /** 초안 — 언제든 고칠 수 있다 */
        DRAFT,
        /** 계획 확정 — 예약 완료를 뜻하지 않는다(PRD F-06) */
        CONFIRMED
    }

    /** 목적지를 어떻게 정했는지. 랜덤 유입과 직접 선택을 섞어 집계하지 않는다(PRD F-04) */
    public enum Entry { RANDOM, DIRECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자 회원 id. 비회원 초안이면 null */
    @Column(name = "user_id")
    private Long userId;

    /** 비회원 초안의 소유 키(세션 id). 회원 계획이면 null */
    @Column(name = "guest_key", length = 64)
    private String guestKey;

    @Column(name = "sig_cd", length = 5, nullable = false)
    private String sigCd;

    @Column(nullable = false)
    private String title;

    /** 1~7일 */
    @Column(name = "day_count", nullable = false)
    private int dayCount = 1;

    /** 날짜 지정은 선택 사항 — 없으면 Day 번호로만 다닌다(PRD F-06) */
    @Column(name = "start_date")
    private LocalDate startDate;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.DRAFT;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Entry entry = Entry.DIRECT;

    /**
     * 이 계획을 만들 때 쓴 성향 스냅샷.
     * 나중에 성향을 고쳐도 이미 만든 일정이 조용히 바뀌지 않도록 여기에 박아 둔다(PRD 6.1).
     */
    @Column(name = "mbti_code", length = 4)
    private String mbtiCode;

    @Column(name = "preference_tags", length = 200)
    private String preferenceTags;

    /** 성향을 실제로 입력받았는지. false 면 화면에 '취향 미반영'을 표시한다 */
    @Column(name = "preference_answered", nullable = false)
    private boolean preferenceAnswered;

    /**
     * 낙관적 버전. 저장할 때 클라이언트가 들고 있던 버전과 비교해
     * 뒤늦은 저장이 앞선 편집을 조용히 덮어쓰지 않게 한다(PRD F-09).
     */
    @Column(nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayIndex asc")
    private List<TripDay> days = new ArrayList<>();

    public void addDay(TripDay day) {
        day.setPlan(this);
        days.add(day);
    }

    public TripDay day(int dayIndex) {
        return days.stream().filter(d -> d.getDayIndex() == dayIndex).findFirst().orElse(null);
    }

    public boolean isConfirmed() {
        return status == Status.CONFIRMED;
    }

    /** 모든 Day 에 관광지가 하나 이상 있어야 확정할 수 있다(PRD F-06) */
    public boolean canConfirm() {
        return !days.isEmpty() && days.stream().allMatch(TripDay::hasSight);
    }

    /** 확정을 막고 있는 Day 번호 — 화면에 그대로 알려준다 */
    public List<Integer> emptyDayIndexes() {
        return days.stream().filter(d -> !d.hasSight()).map(TripDay::getDayIndex).toList();
    }

    public LocalDate dateOf(int dayIndex) {
        return startDate == null ? null : startDate.plusDays(dayIndex - 1L);
    }
}

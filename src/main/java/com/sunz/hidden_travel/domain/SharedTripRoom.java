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

import java.time.LocalDateTime;

/** 가입하지 않은 일행도 링크로 참여할 수 있는 여행 후보 결정방. */
@Entity
@Table(name = "shared_trip_room", indexes = @Index(name = "idx_trip_room_code", columnList = "share_code", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class SharedTripRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_code", nullable = false, unique = true, length = 12)
    private String shareCode;

    @Column(nullable = false)
    private String title;

    private String departure;
    private String duration;
    private String companion;
    private int people;
    private String transport;

    @Column(name = "experiences", length = 500)
    private String experiencesCsv;

    @Column(name = "free_text", length = 1000)
    private String freeText;

    /** 레거시 컬럼명은 유지하지만 값은 official-{id} 또는 assembled-{sigCd}-{variant} 코스 키다. */
    @Column(name = "candidate_sig_cds", nullable = false, length = 500)
    private String candidateSigCds;

    /** 레거시 컬럼명은 유지하지만 확정된 코스 키를 저장한다. */
    @Column(name = "selected_sig_cd", length = 64)
    private String selectedSigCd;

    @Column(nullable = false, length = 20)
    private String status = "COLLECTING";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

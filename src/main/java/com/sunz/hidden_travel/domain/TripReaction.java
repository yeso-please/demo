package com.sunz.hidden_travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_reaction",
        indexes = @Index(name = "idx_trip_reaction_room", columnList = "room_code"),
        uniqueConstraints = @UniqueConstraint(name = "uk_trip_reaction_member_candidate",
                columnNames = {"room_code", "candidate_sig_cd", "nickname"}))
@Getter
@Setter
@NoArgsConstructor
public class TripReaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false, length = 12)
    private String roomCode;

    /** 레거시 컬럼명은 유지하지만 반응 대상 코스 키를 저장한다. */
    @Column(name = "candidate_sig_cd", nullable = false, length = 64)
    private String candidateSigCd;

    @Column(nullable = false, length = 24)
    private String nickname;

    @Column(nullable = false, length = 16)
    private String reaction;

    @Column(length = 300)
    private String comment;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}

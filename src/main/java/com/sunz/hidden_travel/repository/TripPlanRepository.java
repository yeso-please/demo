package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.TripPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripPlanRepository extends JpaRepository<TripPlan, Long> {

    /** 내 여행 목록 (최근 수정 순) */
    List<TripPlan> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /** 비회원 초안 — 같은 세션에서만 다시 찾을 수 있다 */
    List<TripPlan> findByGuestKeyOrderByUpdatedAtDesc(String guestKey);
}

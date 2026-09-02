package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.Diary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiaryRepository extends JpaRepository<Diary, Long> {

    /** 내 다이어리 — 최근 편이 먼저 */
    List<Diary> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 공개된 편만 — 다른 사람의 지도를 열어볼 때 */
    List<Diary> findByUserIdAndSharedTrueOrderByCreatedAtDesc(Long userId);

    /** 피드 */
    List<Diary> findTop50BySharedTrueOrderByCreatedAtDesc();

    boolean existsByUserIdAndSigCd(Long userId, String sigCd);

    long countByUserId(Long userId);
}

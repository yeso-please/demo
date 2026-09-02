package com.sunz.hidden_travel.init;

import com.sunz.hidden_travel.service.DiaryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 기존 후기를 다이어리로 옮긴다 (1회성, 매 기동마다 안전하게 재실행 가능).
 *
 * <p>후기와 다이어리는 사실 같은 객체였다 — 둘 다 '어느 지역에 대해 쓴 글'이고,
 * 후기에만 사진·공유가, 다이어리에만 취향 읽기가 붙어 있었다.
 * 합치면 다녀온 여행이 다음 추천을 좋게 만드는 고리가 닫힌다.
 *
 * <p>이미 옮긴 편은 건너뛰므로(사용자+지역 기준) 여러 번 떠도 중복되지 않는다.
 * 원본 {@code review} 테이블은 손대지 않는다.
 */
@Component
@Order(100)
public class ReviewToDiaryMigration implements ApplicationRunner {

    private final DiaryService diaryService;

    public ReviewToDiaryMigration(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        diaryService.importReviews();
    }
}

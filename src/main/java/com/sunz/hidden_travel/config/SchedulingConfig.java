package com.sunz.hidden_travel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 스케줄러 활성화.
 *
 * 현재 걸려 있는 작업:
 *  - {@code AttractionDetailBackfillService#scheduled()} — 관광지 상세 설명 선적재 (매일 04:00 KST)
 *
 * 개별 작업을 끄려면 각자의 설정값을 쓴다(예: {@code tour.api.backfill.enabled: false}).
 * 여기서 통째로 끄면 나중에 추가되는 배치까지 함께 멈춘다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

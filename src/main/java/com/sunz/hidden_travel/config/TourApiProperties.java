package com.sunz.hidden_travel.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TourAPI 설정 (application.yaml: tour.api.*)
 */
@Component
@ConfigurationProperties(prefix = "tour.api")
@Getter
@Setter
public class TourApiProperties {

    /** 공공데이터포털 인증키 */
    private String key;

    /** KorService2 엔드포인트 */
    private String endpoint = "https://apis.data.go.kr/B551011/KorService2";

    /**
     * 1일 호출 한도. 이 수를 넘기면 클라이언트가 호출을 중단한다.
     * 한도를 넘겨 배치가 중간에 실패하면 그날 남은 할당량까지 버리게 되므로,
     * 실제 한도(1000)보다 약간 낮게 잡아 여유를 둔다.
     */
    private int dailyCallLimit = 950;

    /** 관광지 상세 설명 선적재 배치 (tour.api.backfill.*) */
    private Backfill backfill = new Backfill();

    /**
     * 상세 설명 선적재 설정.
     *
     * 임베딩·합성 질의의 재료가 관광지 상세 설명인데 현재 커버리지가 1% 미만이라
     * 매일 남는 호출로 조금씩 채운다(PRD §7 · §13-4 임계 경로).
     */
    @Getter
    @Setter
    public static class Backfill {

        /** 야간 자동 실행 여부. 끄면 /admin 수동 실행만 가능하다 */
        private boolean enabled = true;

        /** 실행 시각 — 예산이 자정(KST)에 초기화된 뒤 이른 새벽에 돈다 */
        private String cron = "0 0 4 * * *";

        /**
         * 낮에 사용자가 '자세히'를 펼칠 몫으로 남겨두는 호출 수.
         * 배치가 예산을 전부 먹으면 그날 하루 종일 상세가 "준비 중"으로만 보인다.
         */
        private int reserveForUsers = 100;
    }
}

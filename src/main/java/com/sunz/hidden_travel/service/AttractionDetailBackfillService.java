package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.config.TourApiProperties;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.tour.TourApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 관광지 상세 설명 <b>선적재 배치</b>.
 *
 * <h2>왜 필요한가</h2>
 * 상세 설명(overview)은 개인화 발견 엔진의 재료다. 의미 임베딩도, 합성 취향 질의도,
 * 파인튜닝도 전부 여기서 출발한다(PRD §13-4). 그런데 현재 커버리지가 1% 미만이라
 * <b>이 배치가 끝나기 전에는 모델 학습을 시작할 수 없다</b> — 임계 경로다.
 *
 * <h2>왜 한 번에 못 채우나</h2>
 * 콘텐츠 1건당 detailCommon2 + detailIntro2 로 <b>호출 2회</b>가 들고,
 * TourAPI 1일 한도는 1,000회(가드 950)다. 6,700여 건을 채우려면 13,000회가 넘어
 * 하루로는 불가능하다. 그래서 <b>매일 남는 예산만큼 조금씩</b> 채운다.
 *
 * <pre>
 * (950 − 사용자 몫 100) ÷ 2회 ≈ 하루 425건  →  6,700건 ÷ 425 ≈ 16일
 * </pre>
 *
 * <h2>설계</h2>
 * <ul>
 *   <li>낮에 사용자가 '자세히'를 눌렀을 때 쓸 예산({@code reserveForUsers})을 남기고 멈춘다.
 *       배치가 다 먹으면 그날 하루 상세가 계속 "준비 중"으로만 보인다.</li>
 *   <li>한 건씩 {@link AttractionDetailService#detail(Long)} 로 처리한다. 그 메서드가
 *       건별 트랜잭션이라 <b>중간에 죽어도 여기까지의 진행은 남는다.</b> 다음 날 이어서 돈다.</li>
 *   <li>응답이 비어 있어도 {@code detailFetched=true} 가 되어 같은 건을 다시 부르지 않는다.</li>
 * </ul>
 */
@Service
public class AttractionDetailBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AttractionDetailBackfillService.class);

    /** 콘텐츠 1건당 드는 호출 수 (detailCommon2 + detailIntro2) */
    private static final int CALLS_PER_ITEM = 2;

    /**
     * 한 건을 처리하려면 최소 이만큼은 남아 있어야 한다.
     *
     * {@code AttractionDetailService.MIN_REMAINING}(5)보다 작으면 그쪽이 조회를 건너뛰고
     * {@code detailFetched} 를 찍지 않는다. 그러면 같은 후보가 계속 되돌아와
     * <b>예산을 쓰지 않으면서 무한히 도는</b> 상태가 된다. 그 경계보다 넉넉히 잡는다.
     */
    private static final int MIN_CALL_HEADROOM = 8;

    /** 후보를 한 번에 읽어오는 크기 — 전량을 메모리에 올리지 않는다 */
    private static final int CHUNK = 200;

    /** 연속 호출 간격 (rate limit 회피) — TourSyncService 와 같은 값 */
    private static final long CALL_DELAY_MS = 150;

    private final AttractionRepository attractionRepository;
    private final AttractionDetailService detailService;
    private final TourApiClient client;
    private final TourApiProperties props;

    /** 겹쳐 도는 것을 막는다 (수동 실행 + 스케줄이 동시에 걸릴 수 있다) */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AttractionDetailBackfillService(AttractionRepository attractionRepository,
                                           AttractionDetailService detailService,
                                           TourApiClient client,
                                           TourApiProperties props) {
        this.attractionRepository = attractionRepository;
        this.detailService = detailService;
        this.client = client;
        this.props = props;
    }

    /**
     * 야간 자동 실행. 예산이 자정(KST)에 초기화된 뒤 이른 새벽에 돈다.
     * 끄려면 {@code tour.api.backfill.enabled: false}.
     */
    @Scheduled(cron = "${tour.api.backfill.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!props.getBackfill().isEnabled()) {
            log.info("[Backfill] tour.api.backfill.enabled=false — 자동 실행을 건너뜁니다.");
            return;
        }
        log.info("[Backfill] 야간 배치 시작");
        run();
    }

    /** 오늘 예산이 허락하는 만큼 상세를 채운다. */
    public Map<String, Object> run() {
        return run(Integer.MAX_VALUE);
    }

    /**
     * 상세를 채운다.
     *
     * @param maxItems 이번 실행에서 처리할 최대 건수. 배포 전 소규모 확인에 쓴다
     *                 (예산은 넉넉해도 몇 건만 돌려보고 결과를 검증할 수 있어야 한다)
     * @return 실행 보고 (관리자 화면·로그용)
     */
    public Map<String, Object> run(int maxItems) {
        if (!running.compareAndSet(false, true)) {
            return Map.of("실행", "이미 진행 중입니다");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        int startRemaining = client.remainingCalls();
        int reserve = Math.max(0, props.getBackfill().getReserveForUsers());

        int done = 0;
        int failed = 0;
        String stopReason;

        String budgetStop = "사용자 몫(" + reserve + "회)을 남기고 중단 — 내일 이어서 돕니다";

        try {
            while (true) {
                if (!hasBudget(reserve)) {
                    stopReason = budgetStop;
                    break;
                }

                if (done + failed >= maxItems) {
                    stopReason = "요청한 " + maxItems + "건 처리 완료";
                    break;
                }

                int take = (int) Math.min(CHUNK, (long) maxItems - (done + failed));
                List<Long> ids = attractionRepository
                        .findDetailBackfillCandidates(AttractionRepository.DETAIL_TARGET_TYPES, PageRequest.of(0, take));
                if (ids.isEmpty()) {
                    stopReason = "남은 대상 없음 — 선적재 완료";
                    break;
                }

                // 이번 묶음에서 실제로 줄었는지 확인할 기준값.
                // 조회가 계속 실패하면 같은 후보가 되돌아오므로 진행 없이 도는 것을 막는다.
                long before = attractionRepository.countDetailBackfillRemaining(AttractionRepository.DETAIL_TARGET_TYPES);

                boolean budgetOut = false;
                for (Long id : ids) {
                    if (!hasBudget(reserve)) {
                        budgetOut = true;
                        break;
                    }
                    try {
                        detailService.detail(id);
                        done++;
                    } catch (Exception e) {
                        // 한 건이 실패해도 배치 전체를 멈추지 않는다.
                        // detailFetched 가 안 찍혔으면 다음 실행에서 다시 시도된다.
                        failed++;
                        log.warn("[Backfill] 상세 조회 실패 id={}: {}", id, e.getMessage());
                    }
                    sleep();
                }
                if (budgetOut) {
                    stopReason = budgetStop;
                    break;
                }
                if (attractionRepository.countDetailBackfillRemaining(AttractionRepository.DETAIL_TARGET_TYPES) >= before) {
                    // 한 묶음을 다 돌았는데 대상이 하나도 줄지 않았다 → 계속 돌아도 같은 결과다
                    stopReason = "진행이 없어 중단 — 실패 " + failed + "건. 로그를 확인하세요";
                    log.error("[Backfill] 한 묶음({}건)을 처리했는데 남은 대상이 줄지 않았습니다. 중단합니다.", ids.size());
                    break;
                }
            }
        } finally {
            running.set(false);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("처리 건수", done);
        report.put("실패", failed);
        report.put("사용한 호출", startRemaining - client.remainingCalls());
        report.put("남은 호출", client.remainingCalls());
        report.put("소요", Duration.between(startedAt, LocalDateTime.now()).toSeconds() + "초");
        report.put("중단 사유", stopReason);
        report.putAll(progress());
        log.info("[Backfill] 완료 → {}", report);
        return report;
    }

    /** 진행률 (API 호출 없음) */
    public Map<String, Object> progress() {
        long total = attractionRepository.count();
        long remaining = attractionRepository.countDetailBackfillRemaining(AttractionRepository.DETAIL_TARGET_TYPES);
        long withDescription = attractionRepository.countWithDescription();
        long fetched = total - remaining;

        int perDay = Math.max(1,
                (props.getDailyCallLimit() - Math.max(0, props.getBackfill().getReserveForUsers()))
                        / CALLS_PER_ITEM);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("관광지 총계", total);
        m.put("상세 조회 완료", fetched);
        m.put("설명 확보", withDescription);          // 조회했지만 원본에 설명이 없는 건도 있다
        m.put("남은 대상", remaining);
        m.put("진행률", total == 0 ? "0%" : String.format("%.1f%%", fetched * 100.0 / total));
        m.put("하루 처리량(예산 기준)", perDay);
        m.put("예상 잔여 일수", (remaining + perDay - 1) / perDay);
        return m;
    }

    /** 한 건을 온전히 처리할 예산이 남았는가 (사용자 몫은 건드리지 않는다) */
    private boolean hasBudget(int reserve) {
        return client.remainingCalls() - CALLS_PER_ITEM >= reserve
                && client.remainingCalls() >= MIN_CALL_HEADROOM;
    }

    private void sleep() {
        try {
            Thread.sleep(CALL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

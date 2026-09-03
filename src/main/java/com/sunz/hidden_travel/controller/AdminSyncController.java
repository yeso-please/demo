package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.goodprice.GoodPriceSyncService;
import com.sunz.hidden_travel.service.AttractionDetailBackfillService;
import com.sunz.hidden_travel.tour.TourSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 관리자용 데이터 적재 트리거. 사용자 요청 경로가 아니며, 배치를 수동 실행한다.
 * (사용자 화면은 항상 DB만 읽는다 — 이 엔드포인트에서만 TourAPI를 호출)
 *
 *  - POST /admin/sync/tour?sigCd=47170  → 단일 지역(안동시) 적재
 *  - POST /admin/sync/tour?areaCode=35  → 시도(경북) 전체 적재
 *  - POST /admin/sync/tour              → 전국 배치
 */
@RestController
@RequestMapping("/admin/sync")
public class AdminSyncController {

    private final TourSyncService sync;
    private final GoodPriceSyncService goodPriceSync;
    private final AttractionDetailBackfillService detailBackfill;

    public AdminSyncController(TourSyncService sync,
                               GoodPriceSyncService goodPriceSync,
                               AttractionDetailBackfillService detailBackfill) {
        this.sync = sync;
        this.goodPriceSync = goodPriceSync;
        this.detailBackfill = detailBackfill;
    }

    @PostMapping("/tour")
    public Map<String, Object> tour(@RequestParam(required = false) String sigCd,
                                    @RequestParam(required = false) Integer areaCode) {
        if (sigCd != null) {
            return sync.syncRegion(sigCd);
        }
        if (areaCode != null) {
            return sync.syncSido(areaCode);
        }
        return sync.syncAll();
    }

    /**
     * 축제·공연·행사 적재 (contentTypeId=15, searchFestival).
     * 행사 기간이 필요해 일반 목록 적재와 오퍼레이션이 다르다.
     *
     * 전국을 한 번에 받는다 — 이 오퍼레이션은 areaCode 필터가 동작하지 않아
     * 시도별로 나눠 받을 수 없다(그리고 나눌 필요도 없다. 전국이 수백 건뿐이다).
     *
     *  - POST /admin/sync/tour/festivals                → 오늘 이후 시작·진행 중인 행사
     *  - POST /admin/sync/tour/festivals?from=20260101  → 시작일 지정(지난 축제까지)
     */
    @PostMapping("/tour/festivals")
    public Map<String, Object> tourFestivals(@RequestParam(required = false) String from) {
        return sync.syncFestivals(from);
    }

    /**
     * 관광지가 비어 있는 지역만 채운다(1일 호출 한도 안에서).
     * 한도가 소진되면 그 지점에서 멈추고, 다음 날 같은 요청을 다시 보내면 이어서 채운다.
     *
     *  - POST /admin/sync/tour/missing
     */
    @PostMapping("/tour/missing")
    public Map<String, Object> tourMissing() {
        return sync.syncMissing();
    }

    /**
     * 이미 적재된 여행코스의 경유지에 contentId·이미지를 채운다(코스당 1회).
     *  - POST /admin/sync/tour/course-points
     */
    @PostMapping("/tour/course-points")
    public Map<String, Object> coursePoints() {
        return sync.refreshCoursePoints();
    }

    /** 이미 적재된 여행코스의 TourAPI detailCommon2.overview를 보정한다. */
    @PostMapping("/tour/course-overviews")
    public Map<String, Object> courseOverviews() {
        return sync.refreshCourseOverviews();
    }

    /** 남은 호출 예산 조회 (API 호출 없음) — GET /admin/sync/tour/budget */
    @GetMapping("/tour/budget")
    public Map<String, Object> budget() {
        return sync.budget();
    }

    /**
     * 관광지 상세 설명 선적재를 <b>지금</b> 실행한다(오늘 예산이 허락하는 만큼).
     * 평소에는 매일 04:00(KST)에 자동으로 돈다 — 이 엔드포인트는 수동 트리거다.
     *
     *  - POST /admin/sync/tour/details            → 오늘 예산만큼
     *  - POST /admin/sync/tour/details?limit=5    → 5건만 (배포 전 확인용)
     */
    @PostMapping("/tour/details")
    public Map<String, Object> tourDetails(@RequestParam(required = false) Integer limit) {
        return limit == null ? detailBackfill.run() : detailBackfill.run(limit);
    }

    /** 선적재 진행률 (API 호출 없음) — GET /admin/sync/tour/details/progress */
    @GetMapping("/tour/details/progress")
    public Map<String, Object> tourDetailsProgress() {
        return detailBackfill.progress();
    }

    /**
     * 착한가격업소 CSV 적재. (URL 한글 회피 위해 시도 2자리 코드 사용)
     *  - POST /admin/sync/goodprice?sidoCode=47 → 경북만
     *  - POST /admin/sync/goodprice             → 전국
     */
    @PostMapping("/goodprice")
    public Map<String, Object> goodPrice(@RequestParam(required = false) String sidoCode) {
        return goodPriceSync.sync(sidoCode);
    }
}

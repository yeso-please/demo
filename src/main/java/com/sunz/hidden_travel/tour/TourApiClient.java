package com.sunz.hidden_travel.tour;

import com.sunz.hidden_travel.config.TourApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 한국관광공사 TourAPI(KorService2) 호출 클라이언트.
 * 국문 관광정보: areaBasedList2 / locationBasedList2 / detailInfo2
 */
@Component
public class TourApiClient {

    private static final Logger log = LoggerFactory.getLogger(TourApiClient.class);

    private final RestClient client;
    private final String serviceKey;
    private final TourCallBudget budget;
    private final ObjectMapper mapper = new ObjectMapper();

    public TourApiClient(TourApiProperties props, TourCallBudget budget) {
        this.serviceKey = props.getKey();
        this.budget = budget;
        this.client = RestClient.builder().baseUrl(props.getEndpoint()).build();
    }

    /* =========================================================
       호출 예산 — 사용량은 DB 에 기록되어 재시작해도 유지된다
       ========================================================= */

    /** 오늘 남은 호출 가능 횟수 */
    public int remainingCalls() {
        return budget.remaining();
    }

    public int usedCalls() {
        return budget.used();
    }

    public int dailyLimit() {
        return budget.dailyLimit();
    }

    /** 한 페이지 결과 */
    public record TourPage(List<JsonNode> items, int totalCount) {
        public static TourPage empty() {
            return new TourPage(List.of(), 0);
        }
    }

    /** 지역(시도) 기반 목록 */
    public TourPage areaBasedList(int areaCode, int contentTypeId, int pageNo, int numOfRows) {
        return call(b -> b.path("/areaBasedList2")
                .queryParam("serviceKey", serviceKey)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Sumeun")
                .queryParam("_type", "json")
                .queryParam("arrange", "O")
                .queryParam("numOfRows", numOfRows)
                .queryParam("pageNo", pageNo)
                .queryParam("contentTypeId", contentTypeId)
                .queryParam("areaCode", areaCode)
                .build());
    }

    /** 좌표(반경) 기반 목록 */
    public TourPage locationBasedList(double lng, double lat, int radiusMeters,
                                      int contentTypeId, int pageNo, int numOfRows) {
        return call(b -> b.path("/locationBasedList2")
                .queryParam("serviceKey", serviceKey)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Sumeun")
                .queryParam("_type", "json")
                .queryParam("arrange", "E")
                .queryParam("numOfRows", numOfRows)
                .queryParam("pageNo", pageNo)
                .queryParam("contentTypeId", contentTypeId)
                .queryParam("mapX", lng)
                .queryParam("mapY", lat)
                .queryParam("radius", radiusMeters)
                .build());
    }

    /**
     * 축제·공연·행사 목록 (contentTypeId=15 전용 오퍼레이션).
     *
     * areaBasedList2 로도 축제를 받을 수 있지만 행사 기간이 응답에 없다.
     * searchFestival2 는 eventstartdate/eventenddate 를 함께 주므로
     * "지금/앞으로 열리는 축제"를 구분할 수 있다.
     *
     * <b>areaCode 를 넘기지 않는다.</b> 실측 결과 이 오퍼레이션은 지역 필터가 동작하지 않는다
     * (응답의 areacode 필드가 비어 있고, areaCode 를 주면 결과가 0건이 된다).
     * 대신 전국을 한 번에 받고 mapx/mapy 로 시군구를 판정한다 — 호출 수도 훨씬 적다.
     *
     * @param eventStartDate yyyyMMdd — 이 날짜 이후에 시작하거나 진행 중인 행사
     */
    public TourPage searchFestival(String eventStartDate, int pageNo, int numOfRows) {
        return call(b -> b.path("/searchFestival2")
                .queryParam("serviceKey", serviceKey)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Sumeun")
                .queryParam("_type", "json")
                .queryParam("numOfRows", numOfRows)
                .queryParam("pageNo", pageNo)
                .queryParam("eventStartDate", eventStartDate)
                .build());
    }

    /**
     * 공통 상세 — overview(상세설명), homepage 등. 콘텐츠 1건당 1회.
     * 결과가 없으면 null.
     */
    public JsonNode detailCommon(String contentId) {
        List<JsonNode> items = call(b -> b.path("/detailCommon2")
                .queryParam("serviceKey", serviceKey)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Sumeun")
                .queryParam("_type", "json")
                .queryParam("contentId", contentId)
                .build()).items();
        return items.isEmpty() ? null : items.get(0);
    }

    /**
     * 유형별 상세 — 관광지(12)는 usetime/restdate/parking/infocenter 등. 콘텐츠 1건당 1회.
     * 결과가 없으면 null.
     */
    public JsonNode detailIntro(String contentId, int contentTypeId) {
        List<JsonNode> items = call(b -> b.path("/detailIntro2")
                .queryParam("serviceKey", serviceKey)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Sumeun")
                .queryParam("_type", "json")
                .queryParam("contentId", contentId)
                .queryParam("contentTypeId", contentTypeId)
                .build()).items();
        return items.isEmpty() ? null : items.get(0);
    }

    /** 여행코스 세부 경유지 (contentTypeId=25) */
    public List<JsonNode> detailInfo(String contentId, int contentTypeId) {
        return call(b -> b.path("/detailInfo2")
                .queryParam("serviceKey", serviceKey)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Sumeun")
                .queryParam("_type", "json")
                .queryParam("numOfRows", 50)
                .queryParam("pageNo", 1)
                .queryParam("contentId", contentId)
                .queryParam("contentTypeId", contentTypeId)
                .build()).items();
    }

    /* ---------- 내부: 호출 + 파싱 ---------- */
    private TourPage call(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn) {
        // 한도를 넘으면 호출하지 않는다. 빈 페이지를 돌려주면 배치 루프가 스스로 멈춘다.
        if (!budget.reserve()) {
            log.warn("[TourAPI] 1일 호출 한도({}) 소진 — 호출을 중단합니다. 내일 이어서 실행하세요.", budget.dailyLimit());
            return TourPage.empty();
        }
        String json;
        try {
            json = client.get().uri(uriFn).retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("[TourAPI] 호출 실패: {}", e.getMessage());
            return TourPage.empty();
        }
        if (json == null || json.isBlank()) return TourPage.empty();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode body = root.path("response").path("body");
            if (body.isMissingNode()) {
                log.warn("[TourAPI] 예상치 못한 응답(앞부분): {}", json.substring(0, Math.min(180, json.length())));
                return TourPage.empty();
            }
            int totalCount = body.path("totalCount").asInt(0);
            JsonNode items = body.path("items");
            List<JsonNode> list = new ArrayList<>();
            if (items.isObject()) {
                JsonNode item = items.path("item");
                if (item.isArray()) {
                    for (int i = 0; i < item.size(); i++) list.add(item.get(i));
                } else if (item.isObject()) {
                    list.add(item);
                }
            }
            return new TourPage(list, totalCount);
        } catch (Exception e) {
            log.warn("[TourAPI] 응답 파싱 실패: {} (앞부분: {})", e.getMessage(),
                    json.substring(0, Math.min(180, json.length())));
            return TourPage.empty();
        }
    }
}

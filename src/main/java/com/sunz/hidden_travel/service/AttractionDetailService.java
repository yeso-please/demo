package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.AttractionDetail;
import com.sunz.hidden_travel.domain.Attraction;
import com.sunz.hidden_travel.geo.SigGeometryService;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.tour.TourApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 관광지 상세를 <b>필요할 때만</b> TourAPI 에서 가져와 DB 에 캐시한다.
 *
 * 상세(detailCommon2 + detailIntro2)는 콘텐츠 1건당 호출 2회가 든다.
 * 전국 관광지 6,768건을 전량 적재하면 13,000회가 넘어 1일 한도(1000회)로는
 * 2주가 걸린다. 그래서 사용자가 카드를 펼쳐 본 관광지만 채운다.
 * 한 번 가져오면 DB 에 남으므로 두 번째부터는 호출이 들지 않는다.
 */
@Service
public class AttractionDetailService {

    private static final Logger log = LoggerFactory.getLogger(AttractionDetailService.class);

    private static final int CT_ATTRACTION = 12;

    /** 상세 조회에 필요한 최소 잔여 예산 (2회 + 여유) */
    private static final int MIN_REMAINING = 5;

    private final AttractionRepository attractionRepository;
    private final TourApiClient client;
    private final SigGeometryService geometry;

    public AttractionDetailService(AttractionRepository attractionRepository,
                                   TourApiClient client,
                                   SigGeometryService geometry) {
        this.attractionRepository = attractionRepository;
        this.client = client;
        this.geometry = geometry;
    }

    /**
     * TourAPI contentId 로 상세를 조회한다(여행코스 경유지용).
     *
     * 경유지는 코스에 딸린 텍스트가 아니라 독립 콘텐츠라서, 이미 적재된 관광지에
     * 같은 contentId 가 있으면 그 행을 그대로 쓰고, 없으면 <b>새 Attraction 으로
     * 만들어 저장</b>한다. 결과적으로 경유지도 관광지와 완전히 동일한 구조로 남는다.
     *
     * @return 조회 실패 시 null
     */
    @Transactional
    public AttractionDetail detailByContentId(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        Attraction existing = attractionRepository.findFirstBySourceContentId(contentId).orElse(null);
        if (existing != null) {
            return detail(existing.getId());
        }
        if (client.remainingCalls() < MIN_REMAINING) {
            // 아직 우리 DB 에 없는 콘텐츠 + 호출 여유 없음 → 이번엔 만들지 않는다
            return null;
        }
        Attraction created = createFromContent(contentId);
        return created == null ? null : detail(created.getId());
    }

    /** contentId 로 TourAPI 를 읽어 Attraction 을 새로 만든다(관광지와 동일한 구조) */
    private Attraction createFromContent(String contentId) {
        JsonNode common = client.detailCommon(contentId);
        if (common == null) {
            return null;
        }
        String name = text(common, "title");
        if (name == null || name.isBlank()) {
            return null;
        }

        Double lng = parseDouble(text(common, "mapx"));
        Double lat = parseDouble(text(common, "mapy"));
        String sigCd = (lng != null && lat != null)
                ? geometry.resolveSigCd(lng, lat).orElse(null)
                : null;
        if (sigCd == null) {
            // 지역을 특정할 수 없으면 지역별 조회에서 유령 데이터가 되므로 저장하지 않는다
            log.warn("[AttractionDetail] 좌표→SIG_CD 실패로 저장 생략: {} ({})", name, contentId);
            return null;
        }

        Attraction a = new Attraction();
        a.setSigCd(sigCd);
        a.setName(name);
        a.setType("관광지");
        a.setAddr(addrOf(common));
        a.setLng(lng);
        a.setLat(lat);
        a.setSourceContentId(contentId);
        a.setImage(firstNonBlank(text(common, "firstimage"), text(common, "firstimage2")));
        a.setDescription(clean(text(common, "overview")));
        a.setHomepage(firstUrl(text(common, "homepage")));

        JsonNode intro = client.detailIntro(contentId, CT_ATTRACTION);
        if (intro != null) {
            a.setUsetime(clean(text(intro, "usetime")));
            a.setRestdate(clean(text(intro, "restdate")));
            a.setParking(clean(text(intro, "parking")));
            a.setInfocenter(clean(text(intro, "infocenter")));
        }
        a.setDetailFetched(true);   // 방금 채웠으므로 다시 호출하지 않는다
        return attractionRepository.save(a);
    }

    /** 상세 조회. 없는 관광지면 null */
    @Transactional
    public AttractionDetail detail(Long attractionId) {
        Attraction a = attractionRepository.findById(attractionId).orElse(null);
        if (a == null) {
            return null;
        }

        boolean pending = false;
        if (!a.isDetailFetched()) {
            if (a.getSourceContentId() == null || a.getSourceContentId().isBlank()) {
                // 외부 콘텐츠가 아니면 더 가져올 게 없다
                a.setDetailFetched(true);
            } else if (client.remainingCalls() < MIN_REMAINING) {
                // 한도가 없으면 이번엔 이름·주소·이미지만 돌려주고, 다음 기회에 다시 시도한다
                log.warn("[AttractionDetail] 호출 한도 부족 — 상세 조회를 건너뜁니다. id={}", attractionId);
                pending = true;
            } else if (!fetchInto(a)) {
                // 예산은 있었는데 응답을 못 받았다 → 화면도 "아직 못 받아옴"으로 알린다
                pending = true;
            }
        }

        return new AttractionDetail(
                a.getId(), a.getName(), a.getAddr(), a.getImage(),
                a.getDescription(), a.getHomepage(), a.getUsetime(), a.getRestdate(),
                a.getParking(), a.getInfocenter(), a.getTel(), pending);
    }

    /**
     * detailCommon2 + detailIntro2 를 읽어 엔티티에 채운다(최대 2회 호출).
     *
     * <p><b>응답을 받았을 때만</b> detailFetched 를 찍는다. 호출 자체가 실패한 건
     * (한도 소진·네트워크 오류) 마킹하면 <b>영영 다시 시도하지 않아 데이터가 비어버린다.</b>
     * 반대로 응답은 왔는데 내용이 비어 있는 건은 원본에 없는 것이므로 마킹한다 —
     * 다시 불러도 같은 결과다.
     */
    private boolean fetchInto(Attraction a) {
        String cid = a.getSourceContentId();
        boolean answered = false;
        try {
            JsonNode common = client.detailCommon(cid);
            if (common != null) {
                answered = true;
                a.setDescription(clean(text(common, "overview")));
                a.setHomepage(firstUrl(text(common, "homepage")));
                if (isBlank(a.getImage())) {
                    a.setImage(firstNonBlank(text(common, "firstimage"), text(common, "firstimage2")));
                }
            }

            JsonNode intro = client.detailIntro(cid, CT_ATTRACTION);
            if (intro != null) {
                answered = true;
                a.setUsetime(clean(text(intro, "usetime")));
                a.setRestdate(clean(text(intro, "restdate")));
                a.setParking(clean(text(intro, "parking")));
                a.setInfocenter(clean(text(intro, "infocenter")));
            }
        } catch (Exception e) {
            log.warn("[AttractionDetail] 상세 조회 실패 contentId={}: {}", cid, e.getMessage());
        }

        if (answered) {
            a.setDetailFetched(true);
        } else {
            log.warn("[AttractionDetail] 응답을 받지 못해 미완료로 둡니다(다음에 재시도) contentId={}", cid);
        }
        return answered;
    }

    /* ---------- 유틸 ---------- */

    private String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    /** TourAPI 본문에는 &lt;br&gt; 등 태그가 섞여 온다 — 화면에서 그대로 보여줄 수 있게 정리 */
    private String clean(String s) {
        if (s == null) {
            return null;
        }
        String out = s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim();
        return out.isBlank() ? null : out;
    }

    /** homepage 필드는 &lt;a href="..."&gt; 형태로 오는 경우가 많다 */
    private String firstUrl(String raw) {
        if (raw == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\"'\\s<>]+").matcher(raw);
        return m.find() ? m.group() : clean(raw);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : (!isBlank(b) ? b : null);
    }

    private Double parseDouble(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            double d = Double.parseDouble(s.trim());
            return d == 0.0 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String addrOf(JsonNode node) {
        String a1 = text(node, "addr1");
        String a2 = text(node, "addr2");
        if (isBlank(a1)) {
            return isBlank(a2) ? null : a2;
        }
        return isBlank(a2) ? a1 : a1 + " " + a2;
    }
}

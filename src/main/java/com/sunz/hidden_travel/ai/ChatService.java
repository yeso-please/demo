package com.sunz.hidden_travel.ai;

import com.sunz.hidden_travel.controller.dto.ChatRecommendation;
import com.sunz.hidden_travel.controller.dto.ChatRequest;
import com.sunz.hidden_travel.controller.dto.ChatResponse;
import com.sunz.hidden_travel.domain.AppUser;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.TravelCourse;
import com.sunz.hidden_travel.mbti.TravelMbtiType;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.TravelCourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 여행 상담 챗봇.
 *
 * 모델이 지역·코스를 지어내지 않도록, <b>우리 DB 에 실재하는 목록을 프롬프트에 넣고</b>
 * 그 안에서만 고르게 한다. 응답으로 받은 sigCd·courseId 는 다시 DB 로 확인한 뒤에만
 * 이동 버튼으로 만든다(모델이 만든 링크는 절대 쓰지 않는다).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 프롬프트에 넣을 최대 코스 수 — 너무 길면 응답이 느려진다 */
    private static final int MAX_COURSES = 400;

    /** 화면에 띄울 최대 추천 개수 */
    private static final int MAX_RECOMMENDATIONS = 4;

    /** 최근 대화 몇 턴까지 모델에 넘길지 */
    private static final int MAX_HISTORY = 8;

    private final GeminiClient gemini;
    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final TravelCourseRepository travelCourseRepository;
    private final com.sunz.hidden_travel.user.CurrentUserService currentUserService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 카탈로그는 자주 바뀌지 않아 한 번 만들어 재사용한다 */
    private volatile String cachedCatalog;

    public ChatService(GeminiClient gemini,
                       RegionRepository regionRepository,
                       AttractionRepository attractionRepository,
                       TravelCourseRepository travelCourseRepository,
                       com.sunz.hidden_travel.user.CurrentUserService currentUserService) {
        this.gemini = gemini;
        this.regionRepository = regionRepository;
        this.attractionRepository = attractionRepository;
        this.travelCourseRepository = travelCourseRepository;
        this.currentUserService = currentUserService;
    }

    public boolean isConfigured() {
        return gemini.isConfigured();
    }

    @Transactional(readOnly = true)
    public ChatResponse chat(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ChatResponse.error("메시지를 입력해 주세요.");
        }
        if (!gemini.isConfigured()) {
            return ChatResponse.error(
                    "AI 키가 아직 설정되지 않았어요. config/application-secret.yaml 에 gemini.api.key 를 넣고 다시 실행해 주세요.");
        }

        List<GeminiClient.Turn> history = new ArrayList<>();
        if (request.history() != null) {
            List<ChatRequest.ChatTurn> recent = request.history().size() > MAX_HISTORY
                    ? request.history().subList(request.history().size() - MAX_HISTORY, request.history().size())
                    : request.history();
            for (ChatRequest.ChatTurn t : recent) {
                if (t.text() == null || t.text().isBlank()) {
                    continue;
                }
                history.add(new GeminiClient.Turn("assistant".equals(t.role()) ? "model" : "user", t.text()));
            }
        }
        history.add(GeminiClient.Turn.user(request.message()));

        String raw = gemini.generate(systemInstruction() + travelerContext(), history);
        if (raw == null) {
            return ChatResponse.error("지금은 답변을 가져오지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        return parse(raw);
    }

    /* =========================================================
       프롬프트
       ========================================================= */

    private String systemInstruction() {
        return """
                너는 '여행페이지'의 여행 상담사다. 사용자의 기록과 질문을 바탕으로 다음 여행의 한 페이지를 제안한다.

                [답변 규칙]
                - 반드시 한국어로, 친근하고 담백한 말투로 답한다.
                - 답변은 3~5문장으로 짧게. 목록이 필요하면 간단히.
                - 아래 '데이터'에 있는 지역과 코스 중에서만 추천한다. 목록에 없는 곳은 추천하지 않는다.
                - 유명세로 순위를 매기지 말고 사용자가 원하는 분위기와 여행 방식에 맞는 시군구를 추천한다.
                  사용자가 특정 지역을 콕 집어 물으면 그 지역을 그대로 답한다.
                - 추천 이유를 한 문장이라도 곁들인다.
                - 링크나 URL 을 직접 쓰지 마라. 이동 버튼은 시스템이 만든다.

                [출력 형식]
                반드시 아래 JSON 만 출력한다. 다른 텍스트를 덧붙이지 마라.
                {
                  "answer": "사용자에게 보여줄 답변",
                  "regions": ["시군구코드5자리", ...],
                  "courses": [코스ID숫자, ...]
                }
                - regions/courses 는 추천할 게 없으면 빈 배열로 둔다.
                - 지역을 추천했다면 regions 에, 코스를 추천했다면 courses 에 담는다.
                - 각각 최대 3개까지만.

                [데이터]
                """ + catalog();
    }

    /**
     * 로그인 사용자의 여행 MBTI 를 프롬프트에 덧붙인다.
     * 검사를 안 했거나 비로그인이면 빈 문자열 — 성향 정보 없이 답한다.
     */
    private String travelerContext() {
        AppUser user = currentUserService.current();
        TravelMbtiType type = user == null ? null : user.mbtiType();
        if (type == null) {
            return "";
        }
        return """

                [이 사용자의 여행 성향]
                여행 MBTI: %s (%s) — %s
                어울리는 여행: %s
                추천할 때 이 성향을 고려하고, 답변에서 자연스럽게 한 번 언급해라.
                단, 성향을 이유로 데이터에 없는 곳을 지어내면 안 된다.
                """.formatted(type.getCode(), type.getLabel(), type.getTagline(), type.getStyle());
    }

    /** 지역·코스 목록을 컴팩트한 텍스트로 (한 번 만들어 캐시) */
    private String catalog() {
        String cached = cachedCatalog;
        if (cached != null) {
            return cached;
        }

        StringBuilder sb = new StringBuilder();

        // 지역: 관광지가 있는 곳만 (추천해도 보여줄 게 있는 지역)
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : attractionRepository.countBySigCd()) {
            counts.put((String) row[0], ((Number) row[1]).intValue());
        }

        sb.append("## 지역 (형식: 시군구코드|시도 시군구|관광지수)\n");
        for (Region r : regionRepository.findAll()) {
            Integer c = counts.get(r.getSigCd());
            if (c == null || c == 0) {
                continue;   // 보여줄 데이터가 없는 지역은 후보에서 제외
            }
            sb.append(r.getSigCd()).append('|')
              .append(r.getProvince()).append(' ').append(r.getName()).append('|')
              .append(c).append('\n');
        }

        sb.append("\n## 여행코스 (형식: 코스ID|코스명|시군구코드)\n");
        int n = 0;
        for (TravelCourse tc : travelCourseRepository.findAll()) {
            if (tc.getTitle() == null || tc.getTitle().isBlank()) {
                continue;
            }
            if (n++ >= MAX_COURSES) {
                break;
            }
            sb.append(tc.getId()).append('|')
              .append(tc.getTitle().replace('|', ' ')).append('|')
              .append(tc.getSigCd()).append('\n');
        }

        cached = sb.toString();
        cachedCatalog = cached;
        log.info("[Chat] 카탈로그 생성 — {}자", cached.length());
        return cached;
    }

    /* =========================================================
       응답 파싱 + 검증
       ========================================================= */

    private ChatResponse parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(stripFence(raw));
        } catch (Exception e) {
            // JSON 이 아니면 본문만이라도 보여준다
            log.warn("[Chat] JSON 파싱 실패 — 원문을 그대로 사용: {}", e.getMessage());
            return ChatResponse.of(raw.trim(), List.of());
        }

        String answer = root.path("answer").asString();
        if (answer == null || answer.isBlank()) {
            answer = "추천할 만한 곳을 찾지 못했어요. 조건을 조금 바꿔서 다시 물어봐 주세요.";
        }

        List<ChatRecommendation> recs = new ArrayList<>();

        // 지역 — 실재하는 시군구만
        for (JsonNode n : root.path("regions")) {
            if (recs.size() >= MAX_RECOMMENDATIONS) {
                break;
            }
            String sigCd = n.asString();
            if (sigCd == null || sigCd.isBlank()) {
                continue;
            }
            regionRepository.findById(sigCd.trim()).ifPresent(r ->
                    recs.add(new ChatRecommendation("region", r.getName(),
                            r.getProvince(), "/region?sigCd=" + r.getSigCd())));
        }

        // 코스 — 실재하는 코스만
        for (JsonNode n : root.path("courses")) {
            if (recs.size() >= MAX_RECOMMENDATIONS) {
                break;
            }
            Long courseId = n.canConvertToLong() ? n.asLong() : parseLong(n.asString());
            if (courseId == null) {
                continue;
            }
            travelCourseRepository.findById(courseId).ifPresent(tc -> {
                String regionLabel = regionRepository.findById(tc.getSigCd())
                        .map(r -> r.getProvince() + " " + r.getName())
                        .orElse("");
                recs.add(new ChatRecommendation("course", tc.getTitle(), regionLabel,
                        "/course?sigCd=" + tc.getSigCd() + "&courseId=" + tc.getId()));
            });
        }

        return ChatResponse.of(answer.trim(), recs);
    }

    /** 모델이 ```json ... ``` 로 감싸는 경우가 있다 */
    private String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    private Long parseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

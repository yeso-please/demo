package com.sunz.hidden_travel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 여행 '경험' 태그 사전.
 *
 * 지역이 가진 텍스트(관광지 이름·설명·유형)에 어떤 단어가 들어 있는지로
 * 그 지역에서 할 수 있는 경험을 거칠게 추정한다.
 *
 * <p>같은 사전을 {@link PersonalizedTripService}(기존 규칙 기반 추천)와
 * {@link TravelerProfileService}(취향 기반 발견)가 함께 쓴다.
 * 두 곳이 같은 축으로 지역을 읽어야 비교 실험이 성립한다.
 *
 * <p><b>한계</b> — 문자열 포함 검사라 문맥을 못 본다("바다"가 '바다횟집'에도 걸린다).
 * 학습 기반 표현으로 교체할 때 이 클래스를 대체하면 된다.
 */
public final class ExperienceTags {

    private static final Map<String, List<String>> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put("자연", List.of("자연", "생태", "숲", "수목", "계곡", "공원"));
        WORDS.put("바다", List.of("바다", "해변", "해안", "항구", "어촌", "섬"));
        WORDS.put("산", List.of("산", "봉", "등산", "고개", "둘레길"));
        WORDS.put("산책", List.of("산책", "걷", "길", "둘레", "데크", "강변"));
        WORDS.put("골목", List.of("골목", "마을", "거리", "벽화", "근대"));
        WORDS.put("역사", List.of("역사", "문화재", "유적", "서원", "성", "사찰", "박물관"));
        WORDS.put("시장", List.of("시장", "장터", "5일장", "상가"));
        WORDS.put("로컬 음식", List.of("음식", "맛", "향토", "특산", "시장"));
        WORDS.put("카페", List.of("카페", "찻집", "커피"));
        WORDS.put("휴식", List.of("휴식", "조용", "한적", "치유", "힐링"));
        WORDS.put("실내", List.of("박물관", "미술관", "전시", "체험관", "기념관"));
        WORDS.put("체험", List.of("체험", "레포츠", "공방", "축제"));
    }

    private ExperienceTags() {
    }

    /** 태그 목록 (사전 정의 순서 유지) */
    public static List<String> all() {
        return List.copyOf(WORDS.keySet());
    }

    public static Map<String, List<String>> words() {
        return Map.copyOf(WORDS);
    }

    /** 텍스트에 등장하는 태그 목록. 입력은 미리 소문자로 만들어 두는 편이 빠르다. */
    public static List<String> match(String lowerText) {
        if (lowerText == null || lowerText.isBlank()) {
            return List.of();
        }
        return WORDS.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(lowerText::contains))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 태그별 '등장 강도'. 단순 포함 여부(0/1)가 아니라 몇 번 나왔는지를 센다.
     * 지역마다 관광지 수가 달라 그대로 쓰면 큰 도시가 유리하므로,
     * 쓰는 쪽에서 반드시 정규화해야 한다.
     */
    public static Map<String, Integer> countByTag(String lowerText) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (lowerText == null || lowerText.isBlank()) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : WORDS.entrySet()) {
            int n = 0;
            for (String w : e.getValue()) {
                int from = 0;
                while (true) {
                    int at = lowerText.indexOf(w, from);
                    if (at < 0) break;
                    n++;
                    from = at + w.length();
                }
            }
            if (n > 0) {
                out.put(e.getKey(), n);
            }
        }
        return out;
    }

    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * 사용자가 쓴 글에서 경험이 <b>어디에 적혀 있는지</b>까지 돌려준다.
     *
     * <p>태그 목록만 주면 화면은 "왜 이 태그가 나왔는지" 보여줄 수 없다.
     * 다이어리 화면은 본문에 밑줄을 그어 근거를 그대로 드러내므로 위치가 필요하다.
     * 태그를 고르게 하는 대신 <b>쓴 글에서 떠오르게</b> 하는 것이 이 화면의 전제다.
     *
     * <p>같은 자리에 여러 단서가 겹치면 <b>긴 단어를 남긴다</b> —
     * '둘레길'을 '길'로 읽으면 밑줄이 엉뚱한 곳에 걸린다.
     *
     * @return 원문 기준 위치. 겹치지 않으며 시작 위치 오름차순.
     */
    public static List<Span> spans(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.toLowerCase(Locale.ROOT);
        List<Span> found = new java.util.ArrayList<>();

        for (Map.Entry<String, List<String>> e : WORDS.entrySet()) {
            for (String w : e.getValue()) {
                int from = 0;
                while (true) {
                    int at = text.indexOf(w, from);
                    if (at < 0) break;
                    found.add(new Span(at, at + w.length(), e.getKey(), raw.substring(at, at + w.length())));
                    from = at + w.length();
                }
            }
        }

        // 긴 것 우선 → 겹치는 짧은 것 버리기
        found.sort(java.util.Comparator
                .comparingInt((Span s) -> s.end() - s.start()).reversed()
                .thenComparingInt(Span::start));

        List<Span> kept = new java.util.ArrayList<>();
        for (Span s : found) {
            boolean overlaps = kept.stream().anyMatch(k -> s.start() < k.end() && k.start() < s.end());
            if (!overlaps) kept.add(s);
        }
        kept.sort(java.util.Comparator.comparingInt(Span::start));
        return List.copyOf(kept);
    }

    /** 글에서 읽힌 태그 — 등장 순서를 유지한다(먼저 쓴 것이 먼저 온다) */
    public static List<String> tagsFrom(String raw) {
        return spans(raw).stream().map(Span::tag).distinct().toList();
    }

    /**
     * @param start 원문에서의 시작 위치
     * @param end   끝 위치(제외)
     * @param tag   이 자리에서 읽힌 경험 태그
     * @param word  실제로 매칭된 원문 조각
     */
    public record Span(int start, int end, String tag, String word) {
    }
}

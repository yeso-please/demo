package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.domain.AppUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 여행 성향(유형 · 경험 태그 · 제외 조건)을 한곳에서 다룬다.
 *
 * <p>회원은 {@link AppUser} 에, 비회원은 세션에 담는다. 코스를 만들 때는 둘 중 있는 쪽을 쓰되
 * 로그인한 사용자의 계정 값을 우선한다 — 세션에 남은 남의 흔적으로 코스를 만들지 않기 위해서다.
 *
 * <p>미응답을 특정 취향으로 간주하지 않는다(PRD F-01). 태그가 없으면 없는 대로 두고,
 * 코스 생성 쪽에서 '취향 미반영' 상태로 표시한다.
 */
@Service
public class TravelPreferenceService {

    /** 온보딩에서 고를 수 있는 제외 조건. 태그 사전과 달리 사람이 읽는 문장이라 여기서 정의한다 */
    public static final List<String> EXCLUDE_OPTIONS =
            List.of("계단·경사 많은 곳", "물놀이", "야간 이동", "오래 걷기");

    /** 태그는 최대 5개 — 더 받으면 가중치가 평평해져 개인화가 사라진다 */
    public static final int MAX_TAGS = 5;

    private static final String SESSION_MBTI = "pref.mbti";
    private static final String SESSION_TAGS = "pref.tags";
    private static final String SESSION_EXCLUDES = "pref.excludes";

    /** 사전에 있는 태그만 남기고 중복을 없앤 뒤 최대 5개로 자른다 */
    public List<String> normalizeTags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> dict = ExperienceTags.all();
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            if (t == null) {
                continue;
            }
            String v = t.trim();
            if (dict.contains(v) && !out.contains(v) && out.size() < MAX_TAGS) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    public List<String> normalizeExcludes(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            if (t == null) {
                continue;
            }
            String v = t.trim();
            if (EXCLUDE_OPTIONS.contains(v) && !out.contains(v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    public void rememberInSession(HttpSession session, String mbtiCode, List<String> tags, List<String> excludes) {
        if (session == null) {
            return;
        }
        session.setAttribute(SESSION_MBTI, mbtiCode);
        session.setAttribute(SESSION_TAGS, String.join(",", tags));
        session.setAttribute(SESSION_EXCLUDES, String.join(",", excludes));
    }

    /**
     * 이번 여행에 쓸 성향 스냅샷. 회원이면 계정 값, 아니면 세션 값.
     * 둘 다 없으면 빈 스냅샷을 돌려주고, 화면은 '취향 미반영'으로 표시한다.
     */
    public Snapshot snapshot(AppUser user, HttpSession session) {
        if (user != null) {
            return new Snapshot(user.getTravelMbti(), user.experienceTagList(), user.excludeTagList(), true);
        }
        if (session == null) {
            return Snapshot.empty();
        }
        String mbti = (String) session.getAttribute(SESSION_MBTI);
        List<String> tags = normalizeTags(splitCsv((String) session.getAttribute(SESSION_TAGS)));
        List<String> excludes = normalizeExcludes(splitCsv((String) session.getAttribute(SESSION_EXCLUDES)));
        boolean answered = mbti != null || !tags.isEmpty();
        return new Snapshot(mbti, tags, excludes, answered);
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * @param answered 성향을 실제로 입력했는지. false 면 균형형 코스를 만들고 그 사실을 화면에 밝힌다
     */
    public record Snapshot(String mbtiCode, List<String> tags, List<String> excludes, boolean answered) {

        public static Snapshot empty() {
            return new Snapshot(null, List.of(), List.of(), false);
        }

        public boolean hasTags() {
            return tags != null && !tags.isEmpty();
        }

        /** "여유롭게 · 자연 · 산책" 처럼 화면에 그대로 쓸 한 줄 */
        public String summaryText() {
            if (!answered) {
                return "아직 성향을 입력하지 않았어요";
            }
            List<String> parts = new ArrayList<>();
            if (mbtiCode != null) {
                parts.add(mbtiCode);
            }
            parts.addAll(tags);
            return parts.isEmpty() ? "아직 성향을 입력하지 않았어요" : String.join(" · ", parts);
        }
    }
}

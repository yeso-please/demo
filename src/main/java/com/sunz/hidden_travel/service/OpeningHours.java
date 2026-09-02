package com.sunz.hidden_travel.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TourAPI 이용시간 원문에서 <b>확실히 읽히는 것만</b> 뽑아낸다.
 *
 * <h2>왜 보수적으로 읽는가</h2>
 * 실제 데이터를 표본으로 뽑아보니 형태가 제각각이다.
 * <pre>
 * 상시 개방                                              ← 표본의 절반
 * 09:00~18:00
 * - 하절기 09:00~21:00 - 동절기 09:00~20:00
 * - 화요일~목요일 / 일요일 10:00~20:00 - 금요일~토요일 10:00~22:00
 * 10:00 ~ 17:00 매주 월요일 휴관 (월요일이 공휴일인 경우 다음날 휴관)
 * [일일 입장] - 08:00~18:00 [숙박] - 15:00~익일 12:00
 * 전화 문의 / 점포 별로 상이함
 * </pre>
 *
 * 계절·요일·구역별로 시간이 갈리는 원문을 억지로 하나로 접으면 <b>틀린 시각을 단정</b>하게 된다.
 * 닫힌 곳을 열렸다고 말하는 순간 코스 전체를 못 믿게 되므로,
 * 조건이 섞인 원문은 <b>판정하지 않고</b> 원문을 그대로 보여주는 쪽을 택한다.
 *
 * <p>휴무 요일은 별도로 읽는다 — 시간이 맞아도 그날 문을 닫으면 헛걸음이다.
 */
public final class OpeningHours {

    /** "09:00~18:00" · "10:00 ~ 17:00" */
    private static final Pattern RANGE =
            Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[~\\-–]\\s*(\\d{1,2}):(\\d{2})");

    /** "매주 월요일 휴관" · "월요일 휴무" */
    private static final Pattern CLOSED_DAY =
            Pattern.compile("(매주\\s*)?([월화수목금토일])요일[^\\n]{0,6}?(휴관|휴무|정기휴무)");

    /** 시간이 조건에 따라 갈리는 원문 — 하나로 접으면 안 된다 */
    private static final String[] CONDITIONAL = {
            "하절기", "동절기", "월~", "평일", "주말", "요일~", "[", "익일", "회차", "상이"
    };

    private static final String DAYS = "월화수목금토일";

    private OpeningHours() {
    }

    /**
     * @param alwaysOpen    "상시 개방" 처럼 시간 제약이 없는 곳
     * @param openMinutes   자정 기준 여는 시각(분). 못 읽으면 null
     * @param closeMinutes  자정 기준 닫는 시각(분). 못 읽으면 null
     * @param closedWeekday 휴무 요일 (1=월 … 7=일). 없으면 null
     */
    public record Parsed(boolean alwaysOpen, Integer openMinutes, Integer closeMinutes, Integer closedWeekday) {

        /** 시각을 단정할 수 있는 상태인가 */
        public boolean hasRange() {
            return openMinutes != null && closeMinutes != null;
        }

        public static Parsed unknown() {
            return new Parsed(false, null, null, null);
        }
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Parsed.unknown();
        }
        String text = raw.trim();
        Integer closedDay = closedWeekday(text);

        // 1) 조건이 섞인 원문은 무엇도 단정하지 않는다.
        //    '상시' 판정보다 반드시 먼저 와야 한다 —
        //    "[생태공원] 상시 개방 [음악분수(5~10월)] 금요일 …" 처럼
        //    구역마다 다른 원문을 통째로 '상시 개방'이라고 읽으면 안 된다.
        for (String c : CONDITIONAL) {
            if (text.contains(c)) {
                return new Parsed(false, null, null, closedDay);
            }
        }

        // 2) 상시 개방 — 시간 범위가 함께 적혀 있지 않을 때만
        boolean always = (text.contains("상시") || text.contains("24시간") || text.contains("연중무휴"))
                && !RANGE.matcher(text).find();
        if (always) {
            return new Parsed(true, null, null, closedDay);
        }

        // 3) 단순 범위가 정확히 하나일 때만 읽는다.
        //    두 개 이상이면 무엇이 본 시간인지 알 수 없다(입장 마감·휴게시간 등이 섞인다).
        Matcher m = RANGE.matcher(text);
        if (!m.find()) {
            return new Parsed(false, null, null, closedDay);
        }
        int open = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        int close = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
        if (m.find()) {
            return new Parsed(false, null, null, closedDay);   // 범위가 둘 이상
        }
        if (open >= close || close > 24 * 60) {
            return new Parsed(false, null, null, closedDay);    // 자정을 넘기는 표기는 다루지 않는다
        }
        return new Parsed(false, open, close, closedDay);
    }

    private static Integer closedWeekday(String text) {
        Matcher m = CLOSED_DAY.matcher(text);
        if (!m.find()) {
            return null;
        }
        int idx = DAYS.indexOf(m.group(2));
        return idx < 0 ? null : idx + 1;      // 1=월 … 7=일 (java.time.DayOfWeek 와 같은 번호)
    }
}

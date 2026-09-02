package com.sunz.hidden_travel;

import com.sunz.hidden_travel.service.OpeningHours;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 TourAPI 원문 표본으로 검증한다.
 * 여기 있는 문자열은 전부 개발 DB 에서 그대로 뽑은 값이다 — 지어낸 형태가 아니다.
 */
class OpeningHoursTest {

    @Test
    void 상시개방은_항상_열림으로_읽는다() {
        var p = OpeningHours.parse("상시 개방");
        assertTrue(p.alwaysOpen());
        assertFalse(p.hasRange());
    }

    @Test
    void 단순범위는_시각으로_읽는다() {
        var p = OpeningHours.parse("09:00~18:00");
        assertTrue(p.hasRange());
        assertEquals(9 * 60, p.openMinutes());
        assertEquals(18 * 60, p.closeMinutes());
    }

    @Test
    void 계절별로_갈리면_판정하지_않는다() {
        var p = OpeningHours.parse("- 하절기 09:00~21:00 - 동절기 09:00~20:00");
        assertFalse(p.hasRange());
        assertFalse(p.alwaysOpen());
    }

    @Test
    void 요일별로_갈리면_판정하지_않는다() {
        var p = OpeningHours.parse("- 화요일~목요일 / 일요일 10:00~20:00 - 금요일~토요일 10:00~22:00");
        assertFalse(p.hasRange());
    }

    @Test
    void 휴게시간이_섞이면_판정하지_않는다() {
        // 범위가 둘이라 무엇이 본 영업시간인지 알 수 없다
        var p = OpeningHours.parse("08:00~19:00 (휴게시간 11:30~12:00)");
        assertFalse(p.hasRange());
    }

    @Test
    void 구역별로_갈리면_판정하지_않는다() {
        var p = OpeningHours.parse("[일일 입장] - 08:00~18:00 [숙박] - 15:00~익일 12:00");
        assertFalse(p.hasRange());
    }

    @Test
    void 안내문구가_붙어도_범위가_하나면_읽는다() {
        var p = OpeningHours.parse("07:00~22:00 ※ 자세한 사항은 전화문의 요망");
        assertTrue(p.hasRange());
        assertEquals(7 * 60, p.openMinutes());
    }

    @Test
    void 야간제한이_붙으면_범위가_둘이라_판정하지_않는다() {
        var p = OpeningHours.parse("07:00~20:00 ※ 야간시간 이용제한(21:00~익일 07:00)");
        assertFalse(p.hasRange());
    }

    @Test
    void 휴관요일을_읽는다() {
        var p = OpeningHours.parse("10:00 ~ 17:00 매주 월요일 휴관 (월요일이 공휴일인 경우 다음날 휴관), 설날·추석 당일 휴관");
        assertEquals(1, p.closedWeekday());
    }

    @Test
    void 구역별_상시개방은_상시로_단정하지_않는다() {
        // 실측 원문. 생태공원은 상시지만 음악분수는 계절·요일제라
        // 통째로 "상시 개방"이라고 읽으면 틀린 안내가 된다.
        var p = OpeningHours.parse("[생태공원] - 상시 개방 [음악분수(5~10월)] - 금요일(1회) 14:00 - 주말(3회) 11:30");
        assertFalse(p.alwaysOpen());
        assertFalse(p.hasRange());
    }

    @Test
    void 읽을_수_없는_문구는_비워둔다() {
        assertFalse(OpeningHours.parse("전화 문의").hasRange());
        assertFalse(OpeningHours.parse("점포 별로 상이함").hasRange());
        assertFalse(OpeningHours.parse(null).hasRange());
        assertFalse(OpeningHours.parse("  ").alwaysOpen());
    }
}

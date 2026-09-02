package com.sunz.hidden_travel;

import com.sunz.hidden_travel.service.ExperienceTags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 다이어리 본문에서 경험을 읽어내는 규칙 */
class ExperienceTagsSpanTest {

    @Test
    void 글에서_읽은_위치를_돌려준다() {
        String note = "근대건축물 사이 골목을 한참 걸었고, 시장에서 먹은 게 기억에 남는다.";
        List<ExperienceTags.Span> spans = ExperienceTags.spans(note);

        assertFalse(spans.isEmpty());
        // 원문에서 잘라낸 조각이 실제로 그 자리에 있어야 한다 — 밑줄이 엉뚱한 데 걸리면 안 된다
        for (ExperienceTags.Span s : spans) {
            assertEquals(s.word(), note.substring(s.start(), s.end()));
        }
        assertTrue(ExperienceTags.tagsFrom(note).contains("골목"));
        assertTrue(ExperienceTags.tagsFrom(note).contains("시장"));
    }

    @Test
    void 겹치면_긴_단어를_남긴다() {
        // '둘레길' 을 '길' 로 읽으면 밑줄이 한 글자만 걸린다
        List<ExperienceTags.Span> spans = ExperienceTags.spans("둘레길을 걸었다");
        assertEquals(1, spans.stream().filter(s -> s.start() == 0).count());
        assertEquals("둘레", spans.get(0).word().substring(0, 2));
    }

    @Test
    void 위치는_겹치지_않고_순서대로_온다() {
        List<ExperienceTags.Span> spans = ExperienceTags.spans("바다를 보고 카페에 앉았다가 시장을 걸었다");
        for (int i = 1; i < spans.size(); i++) {
            assertTrue(spans.get(i - 1).end() <= spans.get(i).start(),
                    "겹치거나 순서가 뒤집혔다");
        }
    }

    @Test
    void 빈_글은_빈_결과() {
        assertTrue(ExperienceTags.spans(null).isEmpty());
        assertTrue(ExperienceTags.spans("   ").isEmpty());
        assertTrue(ExperienceTags.tagsFrom("아무 단서도 없는 문장").isEmpty());
    }
}

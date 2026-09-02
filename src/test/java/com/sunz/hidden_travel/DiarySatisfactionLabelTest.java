package com.sunz.hidden_travel;

import com.sunz.hidden_travel.service.DiaryService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만족도가 없는 편(옮겨온 후기)이 상세에서 터지지 않아야 한다.
 *
 * <p>실제로 터졌다 — {@code Map.of()} 는 <b>null 키 조회에서 NPE</b> 를 던진다.
 * 후기에는 만족도 항목이 아예 없었으므로 값이 비는 건 예외가 아니라 정상이고,
 * 그 편도 피드와 상세에 그대로 나와야 한다.
 */
class DiarySatisfactionLabelTest {

    private static String label(String code) throws Exception {
        Method m = DiaryService.class.getDeclaredMethod("satisfactionLabel", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, code);
    }

    @Test
    void 만족도가_없으면_null() throws Exception {
        assertThat(label(null)).isNull();
    }

    @Test
    void 모르는_코드여도_터지지_않는다() throws Exception {
        assertThat(label("wat")).isNull();
    }

    @Test
    void 아는_코드는_사람_말로_옮긴다() throws Exception {
        assertThat(label("again")).isEqualTo("또 가고 싶다");
        assertThat(label("bad")).isEqualTo("나와 안 맞았다");
    }
}

package com.sunz.hidden_travel.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTokenFilterTest {

    private final AdminTokenFilter filter = new AdminTokenFilter();

    private void setToken(String token) {
        ReflectionTestUtils.setField(filter, "adminToken", token);
    }

    private MockHttpServletRequest adminRequest() {
        return new MockHttpServletRequest("GET", "/admin/sync/tour/budget");
    }

    @Test
    void 토큰_없이_요청하면_거부된다() throws Exception {
        setToken("secret");
        MockHttpServletRequest request = adminRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reachedController = {false};
        FilterChain chain = (req, res) -> reachedController[0] = true;

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertFalse(reachedController[0]);
    }

    @Test
    void 잘못된_토큰이면_거부된다() throws Exception {
        setToken("secret");
        MockHttpServletRequest request = adminRequest();
        request.addHeader("X-Admin-Token", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { throw new AssertionError("체인까지 도달하면 안 된다"); };

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void 올바른_토큰이면_통과한다() throws Exception {
        setToken("secret");
        MockHttpServletRequest request = adminRequest();
        request.addHeader("X-Admin-Token", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reachedController = {false};
        FilterChain chain = (req, res) -> reachedController[0] = true;

        filter.doFilter(request, response, chain);

        assertTrue(reachedController[0]);
    }

    @Test
    void admin_token_미설정이면_로컬_편의상_통과시킨다() throws Exception {
        setToken("");
        MockHttpServletRequest request = adminRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reachedController = {false};
        FilterChain chain = (req, res) -> reachedController[0] = true;

        filter.doFilter(request, response, chain);

        assertTrue(reachedController[0]);
    }

    @Test
    void admin_경로가_아니면_토큰_없어도_통과한다() throws Exception {
        setToken("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/map");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reachedController = {false};
        FilterChain chain = (req, res) -> reachedController[0] = true;

        filter.doFilter(request, response, chain);

        assertTrue(reachedController[0]);
    }
}

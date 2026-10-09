package com.itjiang.qa.context;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link UserContextFilter} 单元测试。
 *
 * <p>这个过滤器承担隐私边界：用户标识错了会导致 A 的记忆被 B 看到，或者上一个请求的身份
 * 泄漏给下一个请求。所以三类情况必须逐一验证：合法值被接受、非法值被忽略、
 * 以及最关键的 —— <b>请求结束后上下文必须被清理</b>。
 *
 * @author Jiang
 */
class UserContextFilterTest {

    private final UserContextFilter filter = new UserContextFilter();

    /**
     * 每个用例结束后清理，避免测试线程被污染（过滤器本身也会清，这里是双保险）。
     */
    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /**
     * 用一个"记录链内取到的 userId"的假 FilterChain 执行过滤器。
     *
     * @param headerValue X-User-Id 请求头的值，{@code null} 表示不携带
     * @return 链内观察到的 userId
     * @throws Exception 过滤器执行异常
     */
    private String runFilter(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/status");
        if (headerValue != null) {
            request.addHeader(UserContextFilter.USER_ID_HEADER, headerValue);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> observedInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            // 在链内取，模拟真实业务代码的读取时机
            observedInChain.set(UserContext.get());
            // 这里顺带断言"链内确实能取到"，避免测试假通过
            assertThat(UserContext.isPresent()).isEqualTo(observedInChain.get() != null);
        };

        filter.doFilter(request, response, chain);
        return observedInChain.get();
    }

    @Test
    @DisplayName("合法的 UUID 会被识别并放进上下文")
    void shouldAcceptValidUserId() throws Exception {
        String userId = "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";
        assertThat(runFilter(userId)).isEqualTo(userId);
    }

    @Test
    @DisplayName("未携带请求头时上下文为空，且不抛异常")
    void shouldTolerateMissingHeader() throws Exception {
        assertThat(runFilter(null)).isNull();
    }

    @Test
    @DisplayName("非法请求头被静默忽略（含空格、超长、特殊字符）")
    void shouldIgnoreIllegalUserId() throws Exception {
        assertThat(runFilter("bad id with spaces")).isNull();
        assertThat(runFilter("short")).isNull();                    // 少于 8 位
        assertThat(runFilter("a".repeat(65))).isNull();             // 超过 64 位
        assertThat(runFilter("'; DROP TABLE qa_memory; --")).isNull();
    }

    @Test
    @DisplayName("请求结束后上下文必须被清理，防止线程复用导致身份串号")
    void shouldClearContextAfterRequest() throws Exception {
        runFilter("9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f");
        assertThat(UserContext.get())
                .as("过滤器必须在 finally 中清理 ThreadLocal")
                .isNull();
    }

    @Test
    @DisplayName("业务代码抛异常时上下文同样要被清理")
    void shouldClearContextEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/status");
        request.addHeader(UserContextFilter.USER_ID_HEADER, "9f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("模拟业务异常");
        };

        assertThatCode(() -> filter.doFilter(request, response, boom))
                .isInstanceOf(IllegalStateException.class);
        assertThat(UserContext.get())
                .as("即使链路抛异常，finally 也必须清理")
                .isNull();
    }
}

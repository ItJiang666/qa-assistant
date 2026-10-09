package com.itjiang.qa.context;

/**
 * 当前请求的用户标识（决策 19 的载体）。
 *
 * <p>长期记忆必须挂在"人"身上，否则甲的偏好会污染乙的回答。本期不做账号体系，
 * 只用一个前端生成的匿名 UUID 标识主体，由 {@link UserContextFilter} 从请求头
 * {@code X-User-Id} 解析后放入本上下文。
 *
 * <p><b>⚠️ 重要限制：ThreadLocal 不会传播到异步线程。</b>
 * 通过 {@code @Async} 执行的任务（例如记忆抽取）拿不到这里的值，必须把 userId
 * 作为方法参数显式传进去。这是常见踩坑点，不要指望在这里能取到。
 *
 * <p><b>⚠️ 必须在请求结束时清理</b>：Tomcat 复用线程，不清理会把上一个请求的
 * 用户身份泄漏给下一个请求 —— 那是实打实的隐私事故。
 * 清理动作由 {@link UserContextFilter} 在 {@code finally} 中保证。
 *
 * @author Jiang
 */
public final class UserContext {

    /** 承载当前请求用户标识的线程局部变量。 */
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    /**
     * 工具类，禁止实例化。
     */
    private UserContext() {
    }

    /**
     * 设置当前请求的用户标识。
     *
     * @param userId 已验证格式的匿名用户标识，允许为 {@code null}（表示未识别用户）
     */
    public static void set(String userId) {
        if (userId == null) {
            CURRENT_USER.remove();
        } else {
            CURRENT_USER.set(userId);
        }
    }

    /**
     * 获取当前请求的用户标识。
     *
     * @return 用户标识；未携带（如直接用 {@code curl} 调试）时返回 {@code null}
     */
    public static String get() {
        return CURRENT_USER.get();
    }

    /**
     * 是否已识别用户。
     *
     * <p>未识别时业务应降级为"无记忆的临时问答"：不读、也不写长期记忆，
     * 而不是报错 —— 这样 {@code curl} 调试和健康检查都不需要伪造请求头。
     *
     * @return 已识别返回 {@code true}
     */
    public static boolean isPresent() {
        return CURRENT_USER.get() != null;
    }

    /**
     * 清理当前线程的用户标识。
     *
     * <p>由过滤器在 {@code finally} 中调用，防止线程复用导致的身份串号。
     */
    public static void clear() {
        CURRENT_USER.remove();
    }
}

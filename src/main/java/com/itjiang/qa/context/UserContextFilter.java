package com.itjiang.qa.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 把请求头 {@code X-User-Id} 解析到 {@link UserContext}。
 *
 * <p>这是"记忆归属"这件事在整个系统里<b>唯一</b>发生的地方：解析一次、放进 ThreadLocal，
 * 后续 Service 层直接取用，业务代码不必到处传 {@code userId} 参数。
 *
 * <p><b>为什么必须校验格式？</b> 这个值会被原样写进数据库并作为查询条件。
 * 不校验就等于把任意长度的外部字符串放进 SQL 参数与日志，
 * 最轻也是日志被灌爆，重则影响索引效率。这里限定为 8~64 位的
 * 字母 / 数字 / 连字符 / 下划线 —— 标准 UUID（36 位含连字符）天然满足。
 *
 * <p><b>缺失请求头时不报错</b>：降级为"无记忆的临时问答"。
 * 这样 {@code curl} 调试、健康检查、监控探活都不需要伪造身份。
 *
 * @author Jiang
 */
@Component
@Order(1)
public class UserContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);

    /** 用户标识请求头名称。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * 合法用户标识：8~64 位，仅字母、数字、连字符、下划线。
     *
     * <p>上限 64 与数据库列 {@code VARCHAR(64)} 保持一致，避免写入时被静默截断。
     */
    private static final Pattern VALID_USER_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String raw = request.getHeader(USER_ID_HEADER);

        if (raw != null && !raw.isBlank()) {
            String candidate = raw.trim();
            if (VALID_USER_ID.matcher(candidate).matches()) {
                UserContext.set(candidate);
            } else {
                // 只记长度不记内容：非法值可能很长或含敏感字符，打全了是给自己找麻烦
                log.warn("忽略非法的 {} 请求头（长度={}，允许 8~64 位字母/数字/-/_）",
                        USER_ID_HEADER, candidate.length());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // ★ 必须清理：Tomcat 复用线程，不清理会把身份泄漏给下一个请求
            UserContext.clear();
        }
    }
}

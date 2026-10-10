package com.itjiang.qa.common;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 异常回退矩阵的回归测试。
 *
 * <p><b>它锁住的是一条产品承诺</b>：无论哪种故障，用户看到的都必须是一句
 * <b>告诉他该做什么</b>的话，而不是"系统内部错误"。
 * 这条承诺很容易在重构里被悄悄破坏 —— 破坏之后功能测试仍然全绿
 * （接口照样返回、状态码照样合法），只有真正遇到故障的人才会发现。
 *
 * <p>下面按"故障 → 用户看到什么 → 怎么自救"逐条固定下来，
 * 也就是开发文档里那张异常回退矩阵的可执行版本。
 *
 * <p><b>刻意用真实的 LangChain4j 异常类</b>（1.20.2 的
 * {@code dev.langchain4j.exception} 包）而不是伪造字符串：
 * 翻译器是按<b>类名</b>匹配的，只有真实类名才能证明这条路走得通。
 * 顺带一个教训：这份测试第一版写的是凭印象的
 * {@code InsufficientQuotaException} —— 那个类在 1.20.2 里根本不存在，
 * 是查了 jar 才发现的。**凭记忆写的外部类名必须回查**。
 *
 * @author Jiang
 */
class FailureTranslatorTest {

    @Test
    @DisplayName("Ollama 没启动（连接被拒）→ 直接点名 Ollama，并给出默认端口")
    void connectRefusedShouldNameOllama() {
        String message = FailureTranslator.message(new ConnectException("Connection refused"));

        assertThat(message).contains("Ollama").contains("11434");
        assertThat(FailureTranslator.toBusinessException(new ConnectException("x"), "q").getErrorCode())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("域名解析不了（写错 base-url）→ 同样落到连接类话术，而不是通用错误")
    void unknownHostShouldBeTreatedAsUnreachable() {
        String message = FailureTranslator.message(new UnknownHostException("api.deepsek.com"));

        assertThat(message).contains("连接不上").contains("base-url");
    }

    @Test
    @DisplayName("超时 → 提示首次提问要加载模型，并指向显存")
    void timeoutShouldExplainColdStart() {
        String message = FailureTranslator.message(new SocketTimeoutException("timeout"));

        assertThat(message).contains("超时").contains("显存");
    }

    @Test
    @DisplayName("Key 无效（401）→ 说清是 Key 的问题，并给出「改完要重启」")
    void authenticationShouldBlameTheKey() {
        Exception ex = new AuthenticationException("Token is invalid.");

        String message = FailureTranslator.message(ex);

        assertThat(message).contains("API Key").contains("401").contains("重启");
        // 这条是 P6 演练的原始症状：修复前它返回的是「系统内部错误」
        assertThat(message).doesNotContain("内部错误");
    }

    @Test
    @DisplayName("被限流（429）→ 明说「稍等几秒再试」，而不是让用户以为坏了")
    void rateLimitShouldSayWait() {
        String message = FailureTranslator.message(new RateLimitException("rate limited"));

        assertThat(message).contains("限流").contains("再试");
    }

    @Test
    @DisplayName("模型名不存在（404）→ 提示去查模型名，并把 deepseek-chat 已下线这个坑写出来")
    void modelNotFoundShouldPointToModelName() {
        String message = FailureTranslator.message(new ModelNotFoundException("model not found"));

        assertThat(message).contains("模型名").contains("deepseek-flash");
    }

    @Test
    @DisplayName("请求被拒（400）→ 列出三类常见原因，并指向日志里的厂商原文")
    void invalidRequestShouldListCommonCauses() {
        String message = FailureTranslator.message(new InvalidRequestException("bad request"));

        assertThat(message).contains("400").contains("参数").contains("日志");
    }

    @Test
    @DisplayName("余额不足（402）→ 指向充值，并给一条「先切回本地」的退路")
    void paymentRequiredShouldPointToBilling() {
        // LangChain4j 1.20.2 没有配额异常类，只能靠 HttpException 的状态码判断
        String message = FailureTranslator.message(new HttpException(402, "Insufficient Balance"));

        assertThat(message).contains("余额").contains("充值").contains("本地");
    }

    @Test
    @DisplayName("厂商侧 5xx → 明确说「这不是你的问题」，而不是让用户去改配置")
    void serverErrorShouldBlameTheVendor() {
        String message = FailureTranslator.message(new HttpException(503, "upstream busy"));

        assertThat(message).contains("不是你的问题").contains("503");
    }

    @Test
    @DisplayName("异常被层层包裹时仍能识别（真实的异常链都很深）")
    void shouldWalkTheCauseChain() {
        Exception deep = new Exception("外层", new IllegalStateException("中层",
                new AuthenticationException("Token is invalid.")));

        assertThat(FailureTranslator.message(deep)).contains("API Key");
    }

    @Test
    @DisplayName("认不出来的异常返回 null，由调用方决定兜底文案（不假装知道原因）")
    void unknownFailureShouldReturnNull() {
        assertThat(FailureTranslator.message(new IllegalStateException("没见过"))).isNull();
        assertThat(FailureTranslator.toBusinessException(new IllegalStateException("没见过"), "q")
                .getErrorCode()).isEqualTo(ErrorCode.CHAT_MODEL_ERROR);
    }

    @Test
    @DisplayName("异常链成环不会死循环（防御性：深度上限 10）")
    void shouldNotHangOnCyclicCauseChain() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(FailureTranslator.message(a)).isNull();
    }
}

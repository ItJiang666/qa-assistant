package com.itjiang.qa.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 把底层异常翻译成"用户看完知道该做什么"的提示。
 *
 * <h2>为什么值得单独一个类</h2>
 * <p>这段逻辑原本藏在 {@code ChatService} 的两个私有方法里（同步路径一个、流式路径一个），
 * 后果有两个：<b>没法单独测</b>（要起整个服务才能验证一句话文案），
 * 而且<b>两处容易改歪</b>——同一个故障在同步与流式路径给出两种说法，用户会以为是两个问题。
 * 抽出来之后，"故障 → 用户看到什么"这层映射可以被单元测试逐条锁住，
 * 也能当作文档：一个类读下来就是完整的故障话术表。
 *
 * <h2>判据为什么按类名而不是 instanceof</h2>
 * <p>{@code AuthenticationException} / {@code InsufficientQuotaException} /
 * {@code RateLimitException} 来自 {@code langchain4j-core} 的
 * {@code dev.langchain4j.exception} 包，它们<b>一个都不继承</b>
 * {@code ConnectException} 或 {@code SocketTimeoutException}，所以必须单独判。
 * 按<b>类名</b>匹配而不是 {@code instanceof}，是为了让这层翻译不依赖具体版本：
 * 厂商 SDK 换代、异常挪包名时，最坏结果是退回通用文案，而不是编译失败或悄悄漏判。
 *
 * <h2>三件必须分开说的事</h2>
 * <p>P6 故障演练暴露的真实问题：换错 Key 之后提问，接口只回
 * 「系统内部错误（9000）」——与"服务真的崩了"完全无法区分，用户唯一能做的只有重启。
 * 而实际上原因很具体，且三类错误的自救方式完全不同：
 * <b>换 Key</b>（401）、<b>去充值</b>（402）、<b>等一会儿再试</b>（429）。
 * 混成一句"请重试"等于把排查成本全转嫁给用户。
 *
 * @author Jiang
 */
public final class FailureTranslator {

    private static final Logger log = LoggerFactory.getLogger(FailureTranslator.class);

    private FailureTranslator() {
    }

    /**
     * 翻译成业务异常（同步路径用，可以带业务码与 HTTP 状态）。
     *
     * @param ex       原始异常
     * @param question 用户问题，用于日志（只记长度，不记内容）
     * @return 翻译后的业务异常
     */
    public static BusinessException toBusinessException(Exception ex, String question) {
        String message = message(ex);
        if (message != null) {
            return new BusinessException(codeFor(ex), message, ex);
        }
        log.error("调用模型失败（未预期）: 问题长度={}", question == null ? 0 : question.length(), ex);
        return new BusinessException(ErrorCode.CHAT_MODEL_ERROR, null, ex);
    }

    /**
     * 取一句人能看懂的提示（流式路径用，只能在 SSE 里回字符串）。
     *
     * @param error 原始异常
     * @return 提示文案；无法归类时返回 {@code null}
     */
    public static String message(Throwable error) {
        // ★ TLS 握手失败必须排在 ConnectException 之前判：它俩成因完全不同。
        //   ConnectException = 连不上（端口/服务没起）；SSLHandshakeException = 连上了、
        //   但 TLS 协商被对方或中间设备掐断。用户看到的是裸的
        //   "Runtime exception: javax.net.ssl.SSLHandshakeException: The remote host closed
        //   the handshake"，完全不知道该做什么 —— 这是实测反馈里的一次真实卡点。
        if (hasCauseNamed(error, "SSLHandshakeException") || hasCauseNamed(error, "SSLException")) {
            log.warn("调用模型失败（TLS 握手被中断）: {}", rootMessage(error));
            return "HTTPS 握手失败（TLS 协商被中断）。这通常不是你填错了 Key —— "
                    + "Key 不对时厂商会明确回 401，而不是掐断握手。\n"
                    + "常见原因按可能性排序：\n"
                    + "  1. 这台机器的网络策略/防火墙/安全软件不允许访问该厂商域名"
                    + "（校园网、公司内网、部分云主机常见）；\n"
                    + "  2. 中间有 HTTP 代理或 TLS 拦截设备（抓包、审计类软件），"
                    + "而它不被 JDK 信任；\n"
                    + "  3. 厂商域名被 DNS 污染或劫持。\n"
                    + "可以先这样确认是不是环境问题（把域名换成报错里那个）：\n"
                    + "  curl -v https://api.siliconflow.cn/v1/models\n"
                    + "若它同样握手失败，就与程序无关。\n"
                    + "出路：① 换一个能在本网络访问的厂商（配置里的 qa.cloud.embedding.provider "
                    + "可改 dashscope 等，注意换向量模型后**必须重建索引**）；"
                    + "② 或把模型来源切回本地（Ollama），完全不依赖外网。";
        }
        if (hasCause(error, ConnectException.class) || hasCause(error, UnknownHostException.class)) {
            log.warn("调用模型失败（连接不上）: {}", rootMessage(error));
            return "连接不上模型服务。本地模式请确认 Ollama 已启动（默认 http://localhost:11434）；"
                    + "云端模式请检查网络与 base-url。";
        }
        if (hasCause(error, SocketTimeoutException.class)) {
            log.warn("调用模型超时: {}", rootMessage(error));
            return "模型响应超时。首次提问需要加载模型，可能耗时较久；若持续超时请检查显存是否充足。";
        }

        // ---- 以下四类靠 LangChain4j 的异常类型识别（1.20.2 实际存在的类名）----
        if (hasCauseNamed(error, "AuthenticationException")) {
            log.warn("调用模型失败（凭据被拒）: {}", rootMessage(error));
            return "模型服务拒绝了当前 API Key（401）。请检查 .env 里的 Key 是否正确、是否过期，"
                    + "以及它与端点地域是否匹配（百炼的 Key 与地域绑定，跨地域调用会返回 401）。"
                    + "改完 .env 后需要重启应用，可点「系统状态」页的自检确认。";
        }
        if (hasCauseNamed(error, "RateLimitException")) {
            log.warn("调用模型失败（被限流）: {}", rootMessage(error));
            return "请求过于频繁，已被厂商限流（429）。稍等几秒再试即可；"
                    + "持续出现请检查是否达到厂商的并发上限。";
        }
        if (hasCauseNamed(error, "ModelNotFoundException")) {
            log.warn("调用模型失败（模型名不存在）: {}", rootMessage(error));
            return "厂商没有这个模型（404）。请检查 .env 或 application.yml 里的模型名是否写错、"
                    + "是否已下线 —— 例如 deepseek-chat 已于 2026-07-24 停止服务，现役是 deepseek-flash。";
        }
        if (hasCauseNamed(error, "InvalidRequestException")) {
            log.warn("调用模型失败（请求被拒）: {}", rootMessage(error));
            return "厂商认为这个请求不合法（400）。常见原因：模型名不对、参数不被支持"
                    + "（如某模型不接受 temperature / max_tokens），或单次请求内容过长。"
                    + "详见应用日志里的厂商原始报错。";
        }

        // ---- HTTP 402：余额不足。LangChain4j 1.20.2 **没有**专门的配额异常类，
        //      只能靠 HttpException 携带的状态码判断（DeepSeek 用 402 表达余额问题）。
        Integer status = httpStatus(error);
        if (status != null) {
            if (status == 402) {
                log.warn("调用模型失败（余额不足）: {}", rootMessage(error));
                return "云端账户余额不足或额度已用尽（402）。请到厂商控制台充值，"
                        + "或先在「模型来源」里切回本地模型继续使用。";
            }
            if (status == 403) {
                log.warn("调用模型失败（无权限）: {}", rootMessage(error));
                return "厂商拒绝了这次调用（403）。可能是 Key 没有该模型的权限、"
                        + "或账号未完成实名/开通。请到厂商控制台确认模型权限。";
            }
            if (status >= 500) {
                log.warn("调用模型失败（厂商侧故障 {}）: {}", status, rootMessage(error));
                return "模型服务端出错（HTTP " + status + "），这通常不是你的问题。"
                        + "稍后重试；若持续出现可查看厂商状态页。";
            }
        }
        return null;
    }

    /**
     * 从异常链里取厂商返回的 HTTP 状态码。
     *
     * <p>{@code HttpException} 带 {@code statusCode()}，但它可能被包在
     * 更外层的语义异常里（例如 401 那层）。这里按类名取、取不到返回 {@code null}，
     * 用反射是因为那个类在 {@code langchain4j-core} 的 exception 包里、
     * 不值得为一次判空引入编译期依赖 —— 万一未来挪包，也只是退回通用文案。
     *
     * @param error 原始异常
     * @return HTTP 状态码；没有则返回 {@code null}
     */
    private static Integer httpStatus(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if ("HttpException".equals(current.getClass().getSimpleName())) {
                try {
                    Object status = current.getClass().getMethod("statusCode").invoke(current);
                    if (status instanceof Integer value) {
                        return value;
                    }
                } catch (ReflectiveOperationException ex) {
                    log.debug("读取 HttpException 状态码失败: {}", ex.getMessage());
                }
                return null;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 挑一个合适的业务码。
     *
     * <p>连不上依赖时用 {@code UPSTREAM_UNAVAILABLE}（503）而不是 502：
     * 前者对运维的含义是"下游没起来"，后者是"下游起来了但答坏了"——
     * 告警与排班都按这个分。
     *
     * @param ex 原始异常
     * @return 业务码
     */
    private static ErrorCode codeFor(Throwable ex) {
        // TLS 握手失败也归"下游不可用"：从使用者角度看，服务就是不可达的，
        // 而它既不是"参数错"也不是"厂商答坏了"。与 message() 的判断顺序保持一致。
        if (hasCauseNamed(ex, "SSLHandshakeException") || hasCauseNamed(ex, "SSLException")) {
            return ErrorCode.UPSTREAM_UNAVAILABLE;
        }
        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)) {
            return ErrorCode.UPSTREAM_UNAVAILABLE;
        }
        return ErrorCode.CHAT_MODEL_ERROR;
    }

    /**
     * 判断异常链中是否包含指定类型。
     *
     * @param throwable 起始异常
     * @param type      目标类型
     * @return 命中返回 {@code true}
     */
    public static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        // 限制深度，防止异常链成环导致死循环
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断异常链中是否出现指定类名的异常。
     *
     * @param throwable  起始异常
     * @param simpleName 目标异常类名
     * @return 命中返回 {@code true}
     */
    private static boolean hasCauseNamed(Throwable throwable, String simpleName) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (simpleName.equals(current.getClass().getSimpleName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 取最内层异常的消息，用于日志（不含堆栈，避免刷屏）。
     *
     * @param throwable 异常
     * @return 消息
     */
    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current.getCause() != null && depth < 10; depth++) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}

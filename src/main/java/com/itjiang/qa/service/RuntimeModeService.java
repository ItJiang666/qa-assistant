package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.provider.CloudEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运行时对话模式：本地 / 云端。
 *
 * <h2>为什么"重启后回到配置值"是有意的设计，而不是偷懒</h2>
 * <p>云端模式意味着<b>用户的问题与检索到的文档片段会离开本机</b>，这是合规问题，
 * 不是单纯的性能开关。所以"这个部署到底算不算云端模式"必须由<b>配置</b>回答 ——
 * 配置在仓库里、在部署脚本里、能被审计；而一次界面点击如果能永久改变它，
 * 就会出现"某次实验性切换之后再没人记得它一直在往云上传文档"这种事。
 *
 * <p>因此这里的分工是：
 * <ul>
 *   <li>{@code qa.mode}（配置）= 这个部署的<b>默认模式</b>，每次启动都回到它</li>
 *   <li>本类持有的 {@code current} = <b>本次运行内</b>的临时覆盖，供用户"先试试云端"</li>
 * </ul>
 * 界面上必须把这句话写出来，不能让用户以为开关会永久生效。
 *
 * <h2>为什么切到云端要校验</h2>
 * <p>缺 Key、缺 base-url 时切换成功是<b>假成功</b>：用户点完看到"已切到云端"，
 * 接着提问却 401，会以为是云端服务坏了。切换时就拦住，并告诉他缺哪个环境变量。
 *
 * @author Jiang
 */
@Service
public class RuntimeModeService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModeService.class);

    /**
     * 模型来源。
     */
    public enum Mode {
        /** 本地 Ollama。 */
        LOCAL,
        /** 云端 OpenAI 兼容端点。 */
        CLOUD;

        /**
         * 宽松解析字符串。
         *
         * @param raw 配置原文
         * @return 解析结果；无法识别时回退 {@link #LOCAL}
         */
        public static Mode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return LOCAL;
            }
            return "cloud".equalsIgnoreCase(raw.trim()) ? CLOUD : LOCAL;
        }

        /**
         * @return 配置里使用的写法
         */
        public String value() {
            return name().toLowerCase();
        }
    }

    private final QaProperties properties;
    private final CloudEndpointResolver cloudResolver;

    /**
     * 当前生效模式。
     *
     * <p>{@code volatile}：切换在 HTTP 线程，读取在 SSE 工作线程，
     * 必须保证可见性 —— 否则用户切了模式，正在跑的请求可能还读到旧值。
     */
    private volatile Mode current;

    public RuntimeModeService(QaProperties properties, CloudEndpointResolver cloudResolver) {
        this.properties = properties;
        this.cloudResolver = cloudResolver;
        this.current = Mode.from(properties.getMode());
        log.info("对话模式初始值: {}（来自配置 qa.mode）", current.value());
    }

    /**
     * 当前生效模式。
     *
     * @return 当前模式
     */
    public Mode current() {
        return current;
    }

    /**
     * 配置里的默认模式（重启后回到的那个值）。
     *
     * @return 配置模式
     */
    public Mode configured() {
        return Mode.from(properties.getMode());
    }

    /**
     * 当前是否有运行时覆盖（即与配置值不一致）。
     *
     * <p>界面据此显示"已临时切换，重启后回到 X"。
     *
     * @return 与配置值不同则返回 {@code true}
     */
    public boolean overridden() {
        return current != configured();
    }

    /**
     * 切换模式。
     *
     * @param target 目标模式
     * @throws BusinessException 切到云端但端点不完整时抛出
     */
    public void switchTo(Mode target) {
        if (target == Mode.CLOUD) {
            CloudEndpointResolver.ResolvedEndpoint endpoint = cloudResolver.chat();
            if (!endpoint.usable()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "无法切到云端：对话端点配置不完整，缺少 " + endpoint.missingHint()
                                + "。补齐后重启应用再切，或先继续使用本地模型。");
            }
        }
        Mode previous = current;
        current = target;
        log.info("对话模式切换: {} -> {}{}", previous.value(), target.value(),
                overridden() ? "（临时覆盖，重启后回到 " + configured().value() + "）" : "");
    }
}

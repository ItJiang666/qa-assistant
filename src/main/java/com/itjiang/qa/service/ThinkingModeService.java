package com.itjiang.qa.service;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 云端"思考模式"的运行时开关。
 *
 * <h2>为什么需要这个开关（实测逼出来的）</h2>
 * <p>本项目默认给云端对话模型传"关思考"参数（DeepSeek 是
 * {@code thinking: {type: disabled}}），理由是本地实测思考模式慢 16.5 倍（决策 22）。
 * 但 2026-10-10 的对照实测发现：<b>关掉思考会把"资料里明明有的答案"答成"未找到"</b>。
 *
 * <p>同一份 Prompt（1342 字系统提示词 + 2066 字检索资料，正确片段排在第 3 位），各跑 3 次：
 * <table border="1">
 *   <caption>思考开关 × 模型 的实测结果</caption>
 *   <tr><th>组合</th><th>答对</th><th>延迟</th></tr>
 *   <tr><td>flash + 关思考（原默认）</td><td><b>0 / 3</b></td><td>约 1.0 s</td></tr>
 *   <tr><td>flash + 开思考（现默认）</td><td>3 / 3</td><td>约 2.1 s（思考约 700 字）</td></tr>
 *   <tr><td>v4-pro + 关思考</td><td>3 / 3</td><td>约 1.0 s</td></tr>
 *   <tr><td>v4-pro + 开思考</td><td>3 / 3</td><td>约 11.8 s（思考约 1200 字）</td></tr>
 * </table>
 *
 * <p>失败样本里模型甚至把答案抄了出来，却因为"资料没给文件名、无法标注来源"而
 * 判定为"无法作答" —— 也就是说<b>关思考削弱的是"在多条铁律之间做取舍"的能力</b>，
 * 而知识库问答恰恰到处都是这种取舍。
 *
 * <h2>为什么默认是"开"</h2>
 * <p>"答错"和"慢 1 秒"不是同一量级的代价：知识库问答里一句编造或一句
 * "未找到"都会直接让用户放弃这个工具。所以默认取正确率，速度交给用户自己权衡。
 *
 * <h2>为什么与对话模式一样是"重启回到配置值"</h2>
 * <p>因为它会显著影响回答质量与费用，属于"应该被审计"的开关：配置在仓库里、
 * 在部署脚本里，而一次界面点击只影响本次运行。界面上必须写清楚这一点。
 *
 * @author Jiang
 */
@Service
public class ThinkingModeService {

    private static final Logger log = LoggerFactory.getLogger(ThinkingModeService.class);

    private final QaProperties properties;
    private final ObjectProvider<RuntimeModelRegistry> modelRegistry;

    /**
     * 当前生效值。
     *
     * <p>{@code volatile}：切换在 HTTP 线程，读取在模型装配与 SSE 工作线程，
     * 必须保证可见性。
     */
    private volatile boolean current;

    /**
     * @param properties    应用配置
     * @param modelRegistry 模型注册表；用 {@link ObjectProvider} 延迟解析，
     *                      因为注册表反过来依赖本服务（它要读当前值来装配模型），
     *                      直接注入会形成构造器循环依赖
     */
    public ThinkingModeService(QaProperties properties, ObjectProvider<RuntimeModelRegistry> modelRegistry) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.current = properties.getCloud().getChat().isThinking();
        log.info("云端思考模式初始值: {}（来自配置 qa.cloud.chat.thinking）", current ? "开" : "关");
    }

    /**
     * 当前是否开启思考。
     *
     * @return 开启返回 {@code true}
     */
    public boolean current() {
        return current;
    }

    /**
     * 配置里的默认值（重启后回到的那个值）。
     *
     * @return 配置值
     */
    public boolean configured() {
        return properties.getCloud().getChat().isThinking();
    }

    /**
     * 当前是否处于运行时覆盖状态（与配置值不一致）。
     *
     * @return 不一致返回 {@code true}
     */
    public boolean overridden() {
        return current != configured();
    }

    /**
     * 切换思考模式。
     *
     * <p>切换成功后必须让已装配的模型失效：思考开关改的是<b>发给厂商的额外参数</b>，
     * 而参数在装配期就固定在模型实例上了 —— 不丢缓存，开关就只是界面上好看。
     *
     * @param enabled 目标值
     * @return 切换后是否真的发生了变化
     */
    public boolean switchTo(boolean enabled) {
        if (current == enabled) {
            return false;
        }
        current = enabled;
        RuntimeModelRegistry registry = modelRegistry.getIfAvailable();
        if (registry != null) {
            registry.invalidateChatModels();
        }
        log.info("云端思考模式切换: {} -> {}{}", enabled ? "关" : "开", enabled ? "开" : "关",
                overridden() ? "（临时覆盖，重启后回到配置值 " + (configured() ? "开" : "关") + "）" : "");
        return true;
    }
}

package com.itjiang.qa;

import com.itjiang.qa.model.dto.SystemStatusResponse;
import com.itjiang.qa.service.SystemStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用装配与数据层冒烟测试。
 *
 * <p><b>为什么要把数据目录改到临时目录？</b>
 * 默认数据目录是 {@code %USERPROFILE%\.qa-assistant}，那是用户真实使用的位置。
 * 测试若往里写数据，会把开发机的真实库搅乱，甚至在 CI 上污染共享环境。
 *
 * <p>每次运行都用一个新的临时目录，测试之间互不影响；
 * JVM 退出时由 shutdown hook 递归删掉（见 {@link #isolateDataDir}）。
 *
 * @author Jiang
 */
@SpringBootTest
class QaApplicationTests {

    /**
     * 测试专用数据目录。
     *
     * <p>用 {@code @DynamicPropertySource} 而不是 {@code @SpringBootTest(properties = ...)}：
     * 前者允许在运行期生成唯一目录名（{@code createTempDirectory}），
     * 后者只能写死一个路径，多次运行会读到上一次的库文件。
     *
     * <p><b>目录要在 JVM 退出时删掉。</b> 实测每次 {@code mvn test} 都会在
     * {@code %TEMP%} 里留一份（清理时已积了 26 个）。{@code deleteOnExit()} 不行 ——
     * 它只能删空目录，而目录里有 H2 的库文件。因此自己递归删，并用 shutdown hook
     * 保证测试失败、提前退出时同样执行。
     *
     * @param registry 动态属性注册表
     * @throws IOException 临时目录创建失败
     */
    @DynamicPropertySource
    static void isolateDataDir(DynamicPropertyRegistry registry) throws IOException {
        Path tempDir = Files.createTempDirectory("qa-assistant-test-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(tempDir)));
        registry.add("qa.data-dir", () -> tempDir.toString());
    }

    /**
     * 递归删除目录，失败只忽略。
     *
     * <p>收尾清理不该让测试变红：Windows 上 H2 的文件句柄偶尔释放得比
     * shutdown hook 慢，删不掉最多留个空目录，与测试结论无关。
     *
     * @param dir 待删除目录
     */
    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            // Files.walk 自顶向下，反序即"先删文件、再删目录"
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // 清理失败不影响测试结果
        }
    }

    @Autowired
    private SystemStatusService systemStatusService;

    /**
     * 上下文能正常装配即视为骨架可用。
     *
     * <p>这一条能同时验证：数据源可用、建表脚本执行成功、各 Bean 依赖关系正确。
     */
    @Test
    @DisplayName("Spring 上下文可以正常启动")
    void contextLoads() {
        assertThat(systemStatusService).isNotNull();
    }

    /**
     * 系统状态服务应识别出 H2、且初始计数全为 0。
     *
     * <p>如果建表脚本没生效，这里的查询会抛异常并降级为 0 —— 因此还要断言数据库类型，
     * 确保"0"是因为表里确实没数据，而不是因为查询失败。
     */
    @Test
    @DisplayName("系统状态应识别 H2 数据源，初始计数为 0")
    void systemStatusShouldReportH2WithZeroCounts() {
        SystemStatusResponse status = systemStatusService.currentStatus();

        assertThat(status.database()).isEqualTo("h2");
        assertThat(status.runMode()).isEqualTo("local");
        assertThat(status.chatModel()).isEqualTo("qwen3.5:9b");
        assertThat(status.embeddingModel()).isEqualTo("qa-embed:4b");
        assertThat(status.documentCount()).isZero();
        assertThat(status.chunkCount()).isZero();
        assertThat(status.memoryCount()).isZero();
        // 没经过 HTTP 过滤器，UserContext 里没有值
        assertThat(status.userIdentified()).isFalse();
        assertThat(status.dataDir()).isNotBlank();
    }
}

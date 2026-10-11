package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.config.DataDirInitializer;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.entity.Document;
import com.itjiang.qa.model.enums.DocStatus;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传扩展名白名单的回归测试。
 *
 * <p><b>为什么这里要有测试，而不是"看着对就行"：</b>
 * 白名单有两处容易出错，且都是静默的 ——
 * ① 少一个格式（用户上传时才发现被拒，而报错文案写得对不对没人验）；
 * ② 与 {@code qa.ocr.recognizable-extensions} 脱节：允许上传图片、却不让识别，
 * 用户就会看到一份"能传进来、解析必然失败、识别按钮永远置灰"的文件，
 * 只能得出"这系统坏了"的结论。这条不变量在 {@code imageFormatsMustAlsoBeOcrRecognizable} 里钉住。
 *
 * <p>测试会真的往 JUnit 临时目录写文件（不碰用户数据目录）——
 * 这是刻意的：白名单校验就在 {@code upload} 的主干上，绕开它单独测私有方法
 * 会测不到"校验发生在落盘之前"这个真实的顺序。
 *
 * @author Jiang
 */
class DocumentServiceUploadTest {

    /**
     * 用给定配置构造服务，并把 mapper 存进字段供断言使用。
     *
     * @param tempDir    临时数据目录
     * @param properties 配置（数据目录会被改写到 tempDir）
     * @return 待测服务
     */
    private DocumentMapper mapper;

    private DocumentService serviceWith(Path tempDir, QaProperties properties) {
        properties.setDataDir(tempDir.toString());

        mapper = mock(DocumentMapper.class);
        // insert 之后实体才有 ID（真实 MyBatis 会回填自增主键），这里手动模拟
        doAnswer(inv -> {
            inv.getArgument(0, Document.class).setId(1L);
            return 1;
        }).when(mapper).insert(any(Document.class));

        DataDirInitializer dirs = mock(DataDirInitializer.class);
        when(dirs.uploadDir()).thenReturn(tempDir.resolve("uploads"));

        RuntimeModelRegistry registry = mock(RuntimeModelRegistry.class);
        when(registry.embeddingModelName()).thenReturn("test-embed");

        // OcrService 只用于查询"有没有待确认的识别结果"（DocumentDto.ocrPending）。
        // Mockito 对 boolean 返回类型的默认值是 false，正好是"没有待确认结果"这个正常态，
        // 不需要额外 stub —— 显式写出来反而会让人以为这里有什么讲究。
        OcrService ocrService = mock(OcrService.class);

        return new DocumentService(
                mapper,
                mock(DocumentIngestService.class),
                mock(VectorStoreService.class),
                properties,
                dirs,
                registry,
                ocrService);
    }

    /**
     * 造一个上传文件。
     *
     * @param fileName 原始文件名（决定扩展名）
     * @return 待上传文件
     */
    private MockMultipartFile upload(String fileName) {
        return new MockMultipartFile("file", fileName, null, "dummy-content".getBytes());
    }

    @Test
    @DisplayName("图片格式可以上传，且扩展名会归一化成小写（改动前这里会抛「不支持 .jpg」）")
    void shouldAcceptImageFiles(@TempDir Path tempDir) {
        DocumentService service = serviceWith(tempDir, new QaProperties());

        Map<String, String> storedTypes = new LinkedHashMap<>();
        List<String> names = List.of("receipt.jpg", "shot.JPEG", "screen.png", "photo.webp", "scan.bmp");
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            service.upload(upload(name));
            // 扩展名大小写必须归一：库里存的是小写，前端拿它跟可识别清单比对，
            // 若这里存成 "JPEG"，用户传大写扩展名的图就点不动识别按钮
            Document saved = nthInserted(i + 1);
            storedTypes.put(name, saved.getFileType());
            assertThat(saved.getStatus()).isEqualTo(DocStatus.PENDING.getValue());
        }

        assertThat(storedTypes).containsExactly(
                Map.entry("receipt.jpg", "jpg"),
                Map.entry("shot.JPEG", "jpeg"),
                Map.entry("screen.png", "png"),
                Map.entry("photo.webp", "webp"),
                Map.entry("scan.bmp", "bmp"));
    }

    @Test
    @DisplayName("不支持的格式报错时，先说不支持的，再按配置顺序列出支持的")
    void shouldListAllowedExtensionsInConfiguredOrder(@TempDir Path tempDir) {
        QaProperties properties = new QaProperties();
        Set<String> allowed = Arrays.stream(properties.getAllowedExtensions().split(","))
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        DocumentService service = serviceWith(tempDir, properties);

        assertThatThrownBy(() -> service.upload(upload("virus.exe")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持 .exe 格式")
                // 顺序即配置顺序：曾经用 HashSet 收集，报错话术每次顺序都不一样，
                // 用户读到一串乱序格式名，反而难以确认自己的格式在不在里面
                .hasMessageContaining(String.join("、", allowed));
    }

    @Test
    @DisplayName("被拒的文件不能落盘（校验必须发生在写文件之前）")
    void shouldNotStoreRejectedFile(@TempDir Path tempDir) {
        DocumentService service = serviceWith(tempDir, new QaProperties());

        assertThatThrownBy(() -> service.upload(upload("virus.exe")))
                .isInstanceOf(BusinessException.class);

        // 上传目录要么不存在、要么是空的：拒绝一个文件却把它留在磁盘上，
        // 等于每次误传都在偷偷攒垃圾，而界面上什么也看不到
        Path uploads = tempDir.resolve("uploads");
        assertThatNoException().isThrownBy(() -> {
            if (java.nio.file.Files.exists(uploads)) {
                try (var files = java.nio.file.Files.list(uploads)) {
                    assertThat(files).isEmpty();
                }
            }
        });
    }

    @Test
    @DisplayName("文件名没有扩展名时也要给出可读的拒绝理由，而不是空字符串")
    void shouldRejectFileWithoutExtension(@TempDir Path tempDir) {
        DocumentService service = serviceWith(tempDir, new QaProperties());

        assertThatThrownBy(() -> service.upload(upload("README")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    @DisplayName("⚠️ 可上传的图片格式必须同时出现在 OCR 可识别清单里（否则就是死角）")
    void imageFormatsMustAlsoBeOcrRecognizable() {
        QaProperties properties = new QaProperties();
        Set<String> allowed = Arrays.stream(properties.getAllowedExtensions().split(","))
                .map(String::trim).map(String::toLowerCase).collect(Collectors.toSet());
        Set<String> recognizable = Arrays.stream(properties.getOcr().getRecognizableExtensions().split(","))
                .map(String::trim).map(String::toLowerCase).collect(Collectors.toSet());

        // 这几个扩展名在 Tika 眼里提不出任何文字，上传后必然 FAILED，
        // 唯一出路就是 OCR —— 所以两个清单都必须有它们
        Set<String> imageOnly = Set.of("jpg", "jpeg", "png", "webp", "bmp");

        assertThat(allowed).containsAll(imageOnly);
        assertThat(recognizable).containsAll(imageOnly);
    }

    /**
     * 取回第 N 次 insert 的实体。
     *
     * <p>必须带次数：{@code verify(mapper).insert(...)} 默认要求"恰好一次"，
     * 在同一测试里连续上传多个文件时第二次就会失败，而失败信息会指向
     * Mockito 的调用计数 —— 与"扩展名归一化"这个真正要测的点毫无关系，白排查一轮。
     *
     * @param expectedCalls 到目前为止的 insert 次数
     * @return 实体
     */
    private Document nthInserted(int expectedCalls) {
        var captor = ArgumentCaptor.forClass(Document.class);
        verify(mapper, times(expectedCalls)).insert(captor.capture());
        return captor.getValue();
    }
}

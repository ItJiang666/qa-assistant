package com.itjiang.qa.service;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.dto.OcrPreviewResponse;
import com.itjiang.qa.model.dto.OcrResult;
import com.itjiang.qa.model.entity.Document;
import com.itjiang.qa.ocr.OcrEngine;
import com.itjiang.qa.ocr.OcrException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OCR「哪些格式值得识别」判定的回归测试。
 *
 * <p><b>为什么这个判定值得单独测：</b>它决定两件后果完全不同的事 ——
 * 后端放不放行、界面出不出按钮。判定一旦放宽到带文字层的格式（docx/txt），
 * 用户会把原件里清晰的文字换成 OCR 猜出来的近似文本，而且不会察觉；
 * 判定一旦收窄（比如漏掉 jpg），图片就会卡在"能上传、解析必然失败、
 * 按钮却永远置灰"的死角里。两侧都是静默故障，只能靠测试钉住。
 *
 * @author Jiang
 */
class OcrServiceRecognizableExtensionsTest {

    /**
     * 按给定的配置值构造服务。其余依赖都是 mock ——
     * 本测试只碰配置解析，不触发任何 OCR 调用。
     *
     * @param configured 配置里的 recognizable-extensions 值（{@code null} 表示不设置）
     * @return 待测服务
     */
    private OcrService serviceWith(String configured) {
        QaProperties properties = new QaProperties();
        properties.getOcr().setRecognizableExtensions(configured);
        return new OcrService(
                mock(DocumentMapper.class),
                mock(DocumentIngestService.class),
                mock(OcrEngine.class),
                properties);
    }

    @Test
    @DisplayName("默认配置解析为小写集合，且顺序与配置一致")
    void shouldParseConfiguredExtensions() {
        OcrService service = serviceWith("pdf,jpg,jpeg,png,webp,bmp");

        assertThat(service.recognizableExtensions())
                .containsExactly("pdf", "jpg", "jpeg", "png", "webp", "bmp");
    }

    @Test
    @DisplayName("大小写与多余空格不影响解析")
    void shouldNormalizeCaseAndWhitespace() {
        assertThat(serviceWith(" PDF , JPG ,, Png ").recognizableExtensions())
                .containsExactly("pdf", "jpg", "png");
    }

    @Test
    @DisplayName("⚠️ 配置缺失时兜底成 pdf，不能变成功能全废")
    void shouldFallBackToPdfWhenUnset() {
        // 场景：升级了 jar 但 yml 还是旧的（没有这一项）。此时必须保持改动前的行为，
        // 否则"扫描件识别"这个已有功能会在升级后静默消失。
        assertThat(serviceWith(null).recognizableExtensions()).containsExactly("pdf");
        assertThat(serviceWith("").recognizableExtensions()).containsExactly("pdf");
        assertThat(serviceWith("   ").recognizableExtensions()).containsExactly("pdf");
    }

    @Test
    @DisplayName("带文字层的格式绝不参与识别（对它们做 OCR 是把原文换成猜测）")
    void shouldExcludeTextLayerFormatsFromDefaultConfig() {
        // 用 QaProperties 里的真实默认值，而不是测试自己写的那串：
        // 防止"默认值被改宽"这种改动悄悄溜过测试。
        QaProperties defaults = new QaProperties();
        OcrService service = new OcrService(
                mock(DocumentMapper.class),
                mock(DocumentIngestService.class),
                mock(OcrEngine.class),
                defaults);

        List<String> recognizable = List.copyOf(service.recognizableExtensions());
        assertThat(recognizable).contains("pdf", "jpg", "png");
        assertThat(recognizable).doesNotContain("docx", "doc", "txt", "md", "xlsx", "html");
    }

    /**
     * 造一个只填了扩展名与路径的文档实体。
     *
     * @param fileType 扩展名
     * @param path     文件路径
     * @return 实体
     */
    private Document doc(String fileType, Path path) {
        Document d = new Document();
        d.setId(1L);
        d.setFileName("sample." + fileType);
        d.setFileType(fileType);
        d.setFilePath(path.toString());
        return d;
    }

    @Test
    @DisplayName("自动识别入口：格式不可识别时返回 null，而不是抛异常")
    void forIngestShouldReturnNullForUnrecognizableType() {
        // 为什么是 null 而不是异常：调用方（DocumentIngestService）要区分两种"抽不出字" ——
        // ① 扫描件/图片（可识别）→ 自动识别失败要写明 OCR 的真实原因；
        // ② 内容损坏的 docx（不可识别）→ 提示"可以点识别文字"是误导。
        // 用异常表达第②种会让调用方只能靠 catch 猜语义，而且会污染日志。
        OcrService service = serviceWith("pdf,jpg,png");
        assertThat(service.recognizeForIngest(doc("docx", Path.of("whatever.docx")))).isNull();
    }

    @Test
    @DisplayName("自动识别入口：格式可识别但文件不在磁盘上时明确报错，而不是回 null")
    void forIngestShouldThrowWhenFileMissing() {
        OcrService service = serviceWith("pdf,jpg,png");
        assertThatThrownBy(() -> service.recognizeForIngest(doc("jpg", Path.of("C:/definitely/not/here.jpg"))))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("不在磁盘上");
    }

    @Test
    @DisplayName("⚠️ 已有未过期的识别结果时，recognize 不得重跑 OCR")
    void recognizeShouldReusePendingInsteadOfRerunning() {
        // 这条是自动识别的关键收益：识别一份 3 页扫描件实测约 10 秒。
        // 自动识别跑完之后用户点进来核对，若这里又跑一遍，等于白等 10 秒，
        // 日志里还会出现两条耗时，排查时非常容易误判成"识别了两次"。
        QaProperties properties = new QaProperties();
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentIngestService ingest = mock(DocumentIngestService.class);
        OcrEngine engine = mock(OcrEngine.class);

        when(mapper.selectById(1L)).thenReturn(doc("jpg", Path.of("whatever.jpg")));
        when(engine.recognize(any(), any())).thenReturn(new OcrResult(
                1L, "rapidocr", "9.9.9", 1, 1, 0, 0, 42, 0.97, 1234L, "preview-text", "full-text"));

        OcrService service = new OcrService(mapper, ingest, engine, properties);

        // 第一次：真的跑识别
        OcrPreviewResponse first = service.recognize(1L);
        assertThat(first.chars()).isEqualTo(42);
        verify(engine, times(1)).recognize(any(), any());

        // 第二次：应回放缓存，不能再碰引擎
        OcrPreviewResponse second = service.recognize(1L);
        assertThat(second.chars()).isEqualTo(42);
        assertThat(second.preview()).isEqualTo("preview-text");
        verify(engine, times(1)).recognize(any(), any());

        // 未过期的结果必须能被界面看见（否则那份文档会一直显示成红色"失败"）
        assertThat(service.hasPending(1L)).isTrue();
    }

    @Test
    @DisplayName("stash 之后 hasPending 为真，且暂存的质量指标能被原样取回")
    void stashShouldExposePendingAndKeepQualityMetrics() {
        // 自动识别路径：识别发生在 DocumentIngestService，暂存由本类负责。
        // 指标必须一起存 —— 否则用户过一会儿再打开对话框，看到的是一份没有
        // 页数/置信度的空白结果，就没法判断"这次识别靠不靠谱"了。
        QaProperties properties = new QaProperties();
        DocumentMapper mapper = mock(DocumentMapper.class);
        when(mapper.selectById(1L)).thenReturn(doc("jpg", Path.of("whatever.jpg")));

        OcrService service = new OcrService(mapper, mock(DocumentIngestService.class),
                mock(OcrEngine.class), properties);

        assertThat(service.hasPending(1L)).isFalse();

        service.stash(1L, new OcrResult(1L, "rapidocr", "3.10.0", 3, 3, 1, 1, 165, 0.9769, 1885L,
                "preview-text", "full-text"));

        assertThat(service.hasPending(1L)).isTrue();
        OcrPreviewResponse preview = service.recognize(1L);
        assertThat(preview.totalPages()).isEqualTo(3);
        assertThat(preview.pagesWithText()).isEqualTo(3);
        assertThat(preview.imageCount()).isEqualTo(1);
        assertThat(preview.imagesWithText()).isEqualTo(1);
        assertThat(preview.chars()).isEqualTo(165);
        assertThat(preview.avgConfidence()).isEqualTo(0.9769);
        assertThat(preview.engine()).isEqualTo("rapidocr");
        assertThat(preview.preview()).isEqualTo("preview-text");
        // 剩余有效期必须小于等于总 TTL（已经过去了一点），而不是永远回满值
        assertThat(preview.expiresInSeconds()).isLessThanOrEqualTo(30 * 60);
    }
}

package com.itjiang.qa.service;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.ocr.OcrEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}

package com.itjiang.qa.ocr;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.model.dto.OcrResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OCR 输出解析的回归测试。
 *
 * <p><b>这里锁的是 Java 与 Python 之间唯一的契约点。</b> 它很脆：
 * RapidOCR / ONNX Runtime 都会往 stdout 打自己的日志，所以"stdout 就是 JSON"
 * 这个假设是错的。一旦解析写得过于乐观，线上表现是<b>"识别明明成功了、结果却解析不出来"</b> ——
 * 用户看到"识别失败"，而日志里 Python 侧一切正常，排查方向会被完全带偏。
 *
 * @author Jiang
 */
class RapidOcrEngineParseTest {

    private static final String BEGIN = "###QAOCR_BEGIN###";
    private static final String END = "###QAOCR_END###";

    private final RapidOcrEngine engine = new RapidOcrEngine(new QaProperties(), new ObjectMapper());

    @Test
    @DisplayName("含第三方日志时仍能取出 JSON（真实情况就是这样）")
    void shouldExtractPayloadSurroundedByLibraryLogs() {
        String output = """
                [INFO] Using engine_name: onnxruntime
                [INFO] File exists and is valid: .../PP-OCRv6_det_small.onnx
                %s{"ok":true,"engine":"rapidocr","chars":42,"text":"第1条：编号 ZD-1"}%s
                [INFO] done
                """.formatted(BEGIN, END);

        var payload = engine.extractPayload(output);

        assertThat(payload).isNotNull();
        assertThat(payload.path("ok").asBoolean()).isTrue();
        assertThat(payload.path("chars").asInt()).isEqualTo(42);
        assertThat(payload.path("text").asString()).contains("ZD-1");
    }

    @Test
    @DisplayName("JSON 跨多行时也要能取到（脚本用 indent 输出过）")
    void shouldExtractMultilinePayload() {
        String output = BEGIN + """
                {
                  "ok": true,
                  "engine": "rapidocr",
                  "avgConfidence": 0.974,
                  "text": "第一行\\n第二行"
                }
                """ + END;

        var payload = engine.extractPayload(output);

        assertThat(payload).isNotNull();
        assertThat(payload.path("avgConfidence").asDouble()).isEqualTo(0.974);
        assertThat(payload.path("text").asString()).contains("第二行");
    }

    @Test
    @DisplayName("中文必须正确解码 —— 这条防的是 Windows 上 GBK/UTF-8 错配")
    void shouldDecodeChineseCorrectly() {
        String output = BEGIN + "{\"ok\":true,\"text\":\"处理时限 3 个工作日，责任部门第2组。\"}" + END;

        var payload = engine.extractPayload(output);

        assertThat(payload).isNotNull();
        assertThat(payload.path("text").asString())
                .isEqualTo("处理时限 3 个工作日，责任部门第2组。");
    }

    @Test
    @DisplayName("没有哨兵时返回 null，而不是拿日志当 JSON 硬解析")
    void shouldReturnNullWhenSentinelsMissing() {
        assertThat(engine.extractPayload("[INFO] 只有日志，没有哨兵")).isNull();
        assertThat(engine.extractPayload("")).isNull();
        assertThat(engine.extractPayload(null)).isNull();
    }

    @Test
    @DisplayName("哨兵在但 JSON 坏了也要返回 null（不能抛异常把整条链路打断）")
    void shouldReturnNullOnBrokenJson() {
        assertThat(engine.extractPayload(BEGIN + "{这不是 JSON" + END)).isNull();
    }

    @Test
    @DisplayName("质量判据：置信度低或漏页多时提示用户核对")
    void lowQualityShouldFlagSuspiciousResults() {
        OcrResult good = result(0.97, 3, 3);
        assertThat(good.lowQuality()).isFalse();

        OcrResult lowConfidence = result(0.70, 3, 3);
        assertThat(lowConfidence.lowQuality()).isTrue();

        // 3 页只有 1 页识别出文字 → 大概率漏页，必须提醒
        OcrResult missingPages = result(0.97, 3, 1);
        assertThat(missingPages.lowQuality()).isTrue();
    }

    private static OcrResult result(double confidence, int totalPages, int pagesWithText) {
        return new OcrResult(1L, "rapidocr", "3.10.0", totalPages, pagesWithText,
                100, confidence, 1000L, "preview", "text");
    }
}

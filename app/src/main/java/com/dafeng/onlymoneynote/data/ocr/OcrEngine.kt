package com.dafeng.onlymoneynote.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本地 OCR：ML Kit 文字识别。全离线，不联网，不上传图片。
 *
 * **两个识别器一起跑**：
 * - [ChineseTextRecognizerOptions]：中文模型，认中文/汉字场景（商家名、支付方式这些中文标签）。
 * - [TextRecognizerOptions.DEFAULT_OPTIONS]：拉丁模型，认数字和英文更准（金额、卡号后四位）。
 *
 * 只用拉丁模型的话，截图里「支付方式」「零钱」这些中文词基本认不出来；
 * 只用中文模型的话，纯数字的准确率反而不如拉丁模型。两个结果合并交给
 * [ReceiptParser] 再挑，命中率明显好于单跑一个。
 */
@Singleton
class OcrEngine @Inject constructor() {

    /**
     * 识别图片里所有文字，按行拼接。
     *
     * 拉丁模型先跑（快），中文模型后跑（慢）。任一失败不整体失败 ——
     * 拿不到中文结果也照样把拉丁结果交给上层解析。
     */
    suspend fun recognize(context: Context, uri: Uri): String {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }

        val latin = runCatching { recognizeWith(image) { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) } }
            .getOrNull().orEmpty()
        val chinese = runCatching { recognizeWith(image) { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) } }
            .getOrNull().orEmpty()

        return merge(latin, chinese)
    }

    private suspend fun recognizeWith(
        image: InputImage,
        factory: () -> com.google.mlkit.vision.text.TextRecognizer
    ): String = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            val recognizer = factory()
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // 把每个 block 的文本单独收进 lines，保留行结构
                    val lines = visionText.textBlocks
                        .flatMap { b -> b.lines.map { l -> l.text } }
                    cont.resume(lines.joinToString("\n"))
                    recognizer.close()
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                    recognizer.close()
                }
        }
    }

    /**
     * 合并两个识别器的结果。
     *
     * 不按行去重（两边的行切分粒度不一样，按行去重会误删）。
     * 用「所有行拼一起」交给解析器 —— 解析器本身有噪音行过滤和金额候选择优，
     * 多喂一点文本比精心去重更有效。
     */
    private fun merge(latin: String, chinese: String): String = buildString {
        if (chinese.isNotBlank()) append(chinese.trim()).append('\n')
        if (latin.isNotBlank()) append(latin.trim())
    }.trim()
}

package com.iccidscanner.app

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * 逐帧识别 ICCID：优先读卡板底部的条码（自带校验，读到即可信，但不含末位字母）；
 * 读不到条码时再用文字识别，且要求连续多帧结果一致才采信，避免看错一位。
 * 回调在分析线程上触发。
 */
class IccidAnalyzer(
    private val onFound: (iccid: String, source: String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var ocrCandidate: String? = null
    private var ocrHits = 0

    @Volatile
    var paused = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (paused || mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        try {
            val barcodes = Tasks.await(barcodeScanner.process(image))
            val fromBarcode = barcodes.firstNotNullOfOrNull { b -> b.rawValue?.let(IccidParser::fromBarcode) }

            val text = Tasks.await(textRecognizer.process(image))
            val fromText = text.textBlocks.asSequence()
                .flatMap { block -> block.lines.map { it.text } + block.text.replace("\n", "") }
                .mapNotNull(IccidParser::fromOcrText)
                .toList()

            if (fromBarcode != null) {
                // 条码里没有卡板上印的末位字母：文字识别结果的前缀与条码一致时，用它补上末位
                val completed = fromText.firstOrNull {
                    it.length == fromBarcode.length + 1 && it.startsWith(fromBarcode)
                }
                val confirmed = completed != null && confirm(completed, SUFFIX_CONFIRM_FRAMES)
                onFound(if (confirmed) completed!! else fromBarcode, IccidParser.SOURCE_BARCODE)
                return
            }

            val ocrValue = fromText.firstOrNull() ?: return
            if (confirm(ocrValue, OCR_CONFIRM_FRAMES)) onFound(ocrValue, IccidParser.SOURCE_OCR)
        } catch (e: Exception) {
            Log.w(TAG, "识别失败", e)
        } finally {
            proxy.close()
        }
    }

    /** 同一结果连续出现 [frames] 帧才返回 true（并清零计数）。 */
    private fun confirm(value: String, frames: Int): Boolean {
        if (value == ocrCandidate) ocrHits++ else {
            ocrCandidate = value
            ocrHits = 1
        }
        if (ocrHits < frames) return false
        ocrCandidate = null
        ocrHits = 0
        return true
    }

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "IccidAnalyzer"
        private const val OCR_CONFIRM_FRAMES = 3
        // 有条码兜底时，前 19 位已可信，只需确认末位
        private const val SUFFIX_CONFIRM_FRAMES = 2
    }
}

package com.iccidscanner.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.iccidscanner.app.databinding.ActivityMainBinding
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: IccidDb
    private val adapter = RecordAdapter(::onRecordLongClick)

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: IccidAnalyzer? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var tone: ToneGenerator? = null

    // 同一张卡留在镜头前会被反复识别，这段时间内不重复提示
    private var lastValue: String? = null
    private var lastSeenAt = 0L

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showStatus("需要相机权限才能扫描，请在系统设置里允许", Color.YELLOW)
        }

    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching { contentResolver.openOutputStream(uri)!!.use(::writeExport) }
                .onSuccess { toast("已保存") }
                .onFailure { toast("保存失败：${it.message}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        db = IccidDb(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.list.adapter = adapter

        binding.btnAdd.setOnClickListener { showManualAdd() }
        binding.btnExport.setOnClickListener { showExport() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnPause.setOnClickListener { togglePause() }
        setupTapToFocus()

        refresh()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        runCatching { analyzer?.close() }
        tone?.release()
        db.close()
    }

    // ---------- 相机 ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            // 条码线条细，分辨率太低读不出来，尽量用 1080p
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1080, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val newAnalyzer = IccidAnalyzer { iccid, source -> runOnUiThread { onIccid(iccid, source) } }
            analysis.setAnalyzer(cameraExecutor, newAnalyzer)
            analyzer = newAnalyzer

            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = binding.preview.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            }
            true
        }
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        camera?.cameraControl?.enableTorch(torchOn)
        binding.btnTorch.text = if (torchOn) "关灯" else "补光"
    }

    private fun togglePause() {
        val a = analyzer ?: return
        a.paused = !a.paused
        binding.btnPause.text = if (a.paused) "继续" else "暂停"
        if (a.paused) showStatus("已暂停扫描", Color.LTGRAY) else showStatus(getString(R.string.hint_scan), Color.WHITE)
    }

    // ---------- 识别结果 ----------

    private fun onIccid(iccid: String, source: String) {
        val now = SystemClock.elapsedRealtime()
        val existing = db.findSameCard(iccid)

        // 先存了条码结果（无末位字母），这次文字识别读到了完整的，补上末位
        if (existing != null && iccid.length > existing.iccid.length) {
            if (db.update(existing.id, iccid, IccidParser.SOURCE_BARCODE_OCR)) {
                showStatus("✓ 已补全末位  ${IccidParser.pretty(iccid)}", Color.rgb(0x69, 0xF0, 0xAE))
                refresh()
            }
            lastValue = iccid.take(SAME_CARD_KEY_LENGTH)
            lastSeenAt = now
            return
        }

        val key = iccid.take(SAME_CARD_KEY_LENGTH)
        if (key == lastValue && now - lastSeenAt < SAME_CARD_SILENCE_MS) {
            lastSeenAt = now
            return
        }
        lastValue = key
        lastSeenAt = now

        if (existing == null && db.insert(iccid, source)) {
            beep(ToneGenerator.TONE_PROP_BEEP)
            vibrate()
            showStatus("✓ 已添加  ${IccidParser.pretty(iccid)}", Color.rgb(0x69, 0xF0, 0xAE))
            refresh()
            binding.list.scrollToPosition(0)
        } else {
            beep(ToneGenerator.TONE_PROP_NACK)
            showStatus("已存在，未重复添加  ${IccidParser.pretty(iccid)}", Color.rgb(0xFF, 0xD5, 0x4F))
        }
    }

    private fun refresh() {
        val records = db.all()
        adapter.submit(records)
        binding.count.text = "共 ${records.size} 条"
        binding.empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String, color: Int) {
        binding.status.text = text
        binding.status.setTextColor(color)
    }

    private fun beep(toneType: Int) {
        tone?.startTone(toneType, 150)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------- 列表操作 ----------

    private fun onRecordLongClick(record: Record) {
        AlertDialog.Builder(this)
            .setTitle(IccidParser.pretty(record.iccid))
            .setItems(arrayOf("修改", "删除")) { _, which ->
                when (which) {
                    0 -> showEdit(record)
                    1 -> {
                        db.delete(record.id)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun showEdit(record: Record) {
        promptIccid("修改 ICCID", record.iccid) { iccid ->
            if (db.update(record.id, iccid)) refresh() else toast("该 ICCID 已存在")
        }
    }

    private fun showManualAdd() {
        promptIccid("手动添加 ICCID", "") { iccid ->
            when {
                db.findSameCard(iccid) != null -> toast("该 ICCID 已存在")
                db.insert(iccid, IccidParser.SOURCE_MANUAL) -> refresh()
                else -> toast("该 ICCID 已存在")
            }
        }
    }

    /** 弹出输入框；格式不像 ICCID 时再确认一次。 */
    private fun promptIccid(title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(initial)
            setSelection(text.length)
            hint = "89860…"
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val iccid = IccidParser.normalize(input.text.toString())
                when {
                    iccid.isEmpty() -> Unit
                    IccidParser.isValid(iccid) -> onDone(iccid)
                    else -> AlertDialog.Builder(this)
                        .setMessage("「$iccid」不像 ICCID（应为 89 开头的 19～20 位），仍然保存吗？")
                        .setPositiveButton("保存") { _, _ -> onDone(iccid) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClear() {
        val count = db.all().size
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle("清空全部记录？")
            .setMessage("将删除全部 $count 条记录，无法恢复。建议先导出表格。")
            .setPositiveButton("清空") { _, _ ->
                db.clear()
                lastValue = null
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 导出 ----------

    private fun showExport() {
        if (db.all().isEmpty()) {
            toast("还没有记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("导出 Excel 表格")
            .setItems(arrayOf("分享（微信 / QQ / 邮件…）", "保存到手机文件夹")) { _, which ->
                when (which) {
                    0 -> shareExport()
                    1 -> saveDocument.launch(exportFileName())
                }
            }
            .show()
    }

    private fun shareExport() {
        runCatching {
            val dir = File(cacheDir, "exports").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val file = File(dir, exportFileName())
            file.outputStream().use(::writeExport)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType(XLSX_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "发送表格"))
        }.onFailure { toast("导出失败：${it.message}") }
    }

    private fun writeExport(out: OutputStream) {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val records = db.all().asReversed()
        val rows = records.mapIndexed { i, r ->
            val (digits, suffix) = IccidParser.split(r.iccid)
            listOf(i + 1, digits, suffix, timeFormat.format(Date(r.createdAt)))
        }
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        XlsxWriter.write(
            out,
            title = "ICCID 扫描记录",
            subtitle = "导出时间 $exportedAt　·　共 ${records.size} 条",
            columns = listOf(
                XlsxWriter.Column("序号", 8.0),
                XlsxWriter.Column("ICCID（数字）", 28.0, XlsxWriter.Kind.MONO),
                XlsxWriter.Column("字母", 8.0, XlsxWriter.Kind.ACCENT),
                XlsxWriter.Column("扫描时间", 22.0),
            ),
            rows = rows,
        )
    }

    private fun exportFileName(): String =
        "ICCID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".xlsx"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val SAME_CARD_SILENCE_MS = 3000L
        // 条码结果不含末位，按前 19 位判断是不是同一张卡
        private const val SAME_CARD_KEY_LENGTH = 19
    }
}

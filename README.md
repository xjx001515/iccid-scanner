# ICCID 扫描

安卓 App：用手机摄像头扫描 SIM 卡板，自动识别 ICCID，存进本地数据库，一键导出 Excel 表格。

## 功能

- **条码优先**：读取卡板底部条码，自带校验，结果可靠（条码不含卡板上印的末位字母）。
- **文字识别补全**：同时识别印刷的 ICCID 数字，连续多帧一致才采信；能读出末位字母（如 `…493M`），自动补到同一张卡的记录上。
- **连续扫描**：识别成功“嘀”一声并震动，自动去重，换下一张卡即可。
- **导出 Excel**：数字和末位字母分两列，带标题、表头、隔行底色；ICCID 以文本保存，不会变成科学计数法。可分享到微信 / QQ，或保存到手机。
- 长按记录可修改或删除；支持手动添加、补光、暂停、点按对焦。
- 识别完全离线（ML Kit 内置模型），不依赖 Google Play 服务，国产手机可用。Android 8.0 及以上。

## 构建

需要 JDK 17 和 Android SDK 34。依赖走阿里云镜像（国内无法直连 Google Maven）。

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 代码结构

| 文件 | 作用 |
|---|---|
| `IccidParser.kt` | ICCID 格式校验、OCR 纠错（O→0、I→1 等）、拆分数字与字母 |
| `IccidAnalyzer.kt` | CameraX 逐帧分析：条码 + 文字识别 |
| `IccidDb.kt` | SQLite 存储，按前 19 位判断同一张卡 |
| `XlsxWriter.kt` | 不依赖 POI 生成带样式的 .xlsx |
| `MainActivity.kt` | 界面、去重提示、导出 |

## 技术栈

Kotlin · CameraX · Google ML Kit（Barcode Scanning / Text Recognition）· SQLite

# 台股前三名 Android App

這個 Android App 直接從臺灣證券交易所和櫃買中心取得公開行情，在手機上計算排行並保存資料。下載新行情需要網路；已保存的排行可離線查看。

## 畫面

- 上方顯示最近交易日的前三名，包含分數、成交量和命中的訊號。
- 下方保留前六個交易日的日期，每天只列前三名股名。
- 開啟 App 會更新資料，也可以按「更新資料」手動同步。

## 排名條件

15 項訊號各計 10 分，總分 150 分。低於 80 分（未命中至少 8 項）、收盤價不高於 10 元或成交量少於 2,000 張的股票不會顯示。先依分數由高至低排序，同分時依成交量由高至低排序。

訊號包括均線、MACD、RSI、KD、區間突破、成交量、OBV、MFI、ADX 和報酬率等技術條件。App 使用交易所公布的未還原日 OHLCV；除權息或股票分割可能影響訊號。

## 建置 APK

1. 使用 Android Studio 開啟 android 資料夾。
2. 使用 JDK 17，並安裝 Android SDK 35 和相容的 Gradle 元件。
3. 選擇 Build > Build APK(s)。

也可以在已安裝 JDK 17、Android SDK 35 與 Gradle 8.9 的 PowerShell 中，從 android 資料夾執行：

gradle --no-daemon --console=plain assembleDebug

Debug APK 會輸出至 app/build/outputs/apk/debug/app-debug.apk。APK、手機資料庫、Gradle 建置輸出和簽章金鑰不屬於原始碼，不會提交到 repository。
# 台股前三名 Android App

這個 App 在手機上下載行情、計算技術訊號並保存資料。分析時不需要電腦、Python、FinLab 帳號或 API 金鑰；手機需要網路才能取得新行情。首次下載後，已保存的排行也能在離線時查看。

行情由 App 直接向[臺灣證券交易所](https://openapi.twse.com.tw/)及[證券櫃檯買賣中心](https://www.tpex.org.tw/zh-tw/mainboard/trading/info/stock-pricing.html)取得。App 只抓上市、上櫃每日 OHLCV 資料，計算則在手機上完成，資料存於 App 私有 SQLite 資料庫。畫面日期使用最新可取得的交易日；今日尚未收盤或遇到假日時，會顯示最近一個有行情的交易日。

首次使用會回補近 115 個日曆日，以便計算 60 日訊號和顯示六個前交易日；進度會顯示在畫面上。後續開啟會更新最近幾天並補抓缺漏日期。若交易所暫時無法連線，App 會保留並顯示手機內已保存的資料。

## 畫面

- 上方：最新交易日的前三名，列出符合門檻檔數、排序依據、收盤價、成交量、分數和命中的訊號。
- 下方：六格，每格顯示一個前交易日的前三名和排序依據。
- 按「更新資料」可手動同步；開啟 App 也會自動同步。

## 15 項訊號

每項 10 分，總分 150 分。少於 8 項訊號（低於 80 分）不顯示。其餘候選需股價高於 10 元、成交量至少 2,000 張。依分數由高到低顯示前三名；分數相同時，成交量較大的排前面。

1. 收盤高於 20 日均線
2. 20 日均線高於 60 日均線
3. 60 日均線上彎
4. 20 日均線上彎
5. MACD 高於訊號線
6. MACD 柱體增強
7. RSI 介於 50 至 70
8. K 值高於 D 值且 K 值低於 80
9. 收盤突破前 20 日高點
10. 收盤突破前 60 日高點
11. 成交量高於前 20 日均量 20%
12. OBV 高於 20 日均線
13. MFI 高於 50
14. ADX 高於 20 且 +DI 高於 -DI
15. 20 日報酬為正

這是可檢視的技術面排序規則，並未宣稱已經過歷史回測或能預測未來報酬。App 使用交易所公布的未還原日 OHLCV；除權息、分割等事件可能影響技術訊號。

## 建置 APK

1. 用 Android Studio 開啟 `android` 資料夾。
2. 使用 JDK 17，讓 Android Studio 安裝 Android SDK 35 和相容的 Gradle 元件。
3. 選擇 **Build > Build APK(s)**。
4. APK 會輸出到 `android/app/build/outputs/apk/debug/app-debug.apk`。

也可以在已安裝 JDK 17、Android SDK 35 與 Gradle 8.9 的 PowerShell 中，從 `android` 資料夾執行 `gradle --no-daemon --console=plain assembleDebug`。本專案已成功建置，debug APK 位於 `android/app/build/outputs/apk/debug/app-debug.apk`。

手機開啟 USB 偵錯並授權這台電腦後，可用 `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` 安裝。

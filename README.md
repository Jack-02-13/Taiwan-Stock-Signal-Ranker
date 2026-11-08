# 台股 15 項訊號排行

本專案提供網頁版和 Android App，使用臺灣證券交易所與櫃買中心的公開行情，依 15 項技術訊號列出台股排行。

## 網頁版

[開啟台股訊號排行網站](https://jack-02-13.github.io/Taiwan-Stock-Signal-Ranker/)

手機或電腦瀏覽器都可以使用。首頁顯示最近交易日的前三名；下方保留前六個交易日的日期，並只列出各日前三名股名。GitHub Actions 會在台灣時間平日約 17:00 更新排行。

## Android App

Android Studio 原始碼位於 android 資料夾。App 直接從交易所取得公開行情，在手機上計算並保存資料；更新行情時需要網路，已保存的排行可離線查看。

Repository 提供 App 原始碼，不附 APK。請用 Android Studio 開啟 android 資料夾並依照 Android 建置說明建置。

## 排名方式

- 使用 15 項技術訊號，每項 10 分，總分 150 分。
- 命中至少 8 項（80 分），且收盤價高於 10 元、成交量至少 2,000 張，才會列入排行。
- 先依分數由高至低排序；同分時依成交量由高至低排序。
- 顯示符合條件的前三名；歷史區保留前六個交易日日期和前三名股名。
- 使用未還原 OHLCV，技術訊號可能受除權息或股票分割影響。

## 專案內容

- site/：GitHub Pages 網頁。
- scripts/：產生網頁排行資料的程式。
- android/：Android Studio 專案。
- .github/workflows/：更新公開排行並部署 site/ 的 GitHub Actions 工作流程。

## 資料與隱私

網站與 App 使用交易所公開行情，不需要個人 API 金鑰。Android App 將已下載行情保存在 App 私有資料庫。Repository 不提交 APK、手機資料庫、建置輸出或簽章金鑰；GitHub Pages 工作流程只發布 site/。

行情來源：[證交所盤後行情](https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX)、[櫃買中心歷史行情](https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes)、[櫃買中心 OpenAPI](https://www.tpex.org.tw/openapi/)。

本排行僅供研究參考，不構成投資建議。
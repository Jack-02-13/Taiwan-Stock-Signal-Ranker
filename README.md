# 台股 15 項訊號排行

[開啟網站](https://jack-02-13.github.io/Taiwan-Stock-Signal-Ranker/)

本 repository 的 `main` 分支發佈 GitHub Pages 網頁版。GitHub Actions 會在台灣時間平日約 17:00 更新公開行情與排行資料；網站不需要個人 API 金鑰或後端伺服器。

## 排名規則

- 使用 15 項技術訊號，每項 10 分；至少命中 8 項（80 分）。
- 收盤價需高於 10 元，成交量至少 2,000 張。
- 先依分數由高至低排序；同分時依成交量由高至低排序。
- 首頁顯示最新交易日前三名，下方列出前六個交易日的前三名與日期。
- Android 版使用相同訊號計算。排行使用未還原 OHLCV，僅供參考，並非投資建議。

## Android 原始碼

Android 專案保存在[備份分支](https://github.com/Jack-02-13/Taiwan-Stock-Signal-Ranker/tree/android-current-backup-2026-11-08/android)，可從 GitHub 下載 ZIP 後用 Android Studio 開啟 `android/`。該分支提供原始碼，不包含 APK、手機資料庫或簽章金鑰。

## 隱私與資料來源

網站只使用證交所與櫃買中心公開行情，排行由 GitHub Actions 計算。原始碼不含個人存取 token 或 API 金鑰；工作流程使用 GitHub 執行時提供的短效 token 更新資料並部署。`main` 分支發佈網站，Android 原始碼位於上述獨立分支。

- [臺灣證券交易所盤後行情](https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX)
- [證券櫃檯買賣中心歷史行情](https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes)
- [櫃買中心 OpenAPI](https://www.tpex.org.tw/openapi/)


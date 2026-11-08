# 台股 15 項訊號排行（網頁版）

這是獨立的 GitHub Pages 網頁版。手機瀏覽器只讀取網站上的公開排行資料；GitHub Actions 從證交所與櫃買中心的公開盤後資料計算排名，每個交易日更新。網站不需要 FinLab、API 金鑰、個人帳號或後端服務。

## 發佈到 GitHub Pages

1. 以 `web/` 資料夾作為 GitHub repository 根目錄，並推送到 `main` 分支；這個資料夾只包含網頁專案檔案。
2. 在 repository 的 **Settings → Pages → Build and deployment**，將 **Source** 設為 **GitHub Actions**。
3. 在 **Actions** 頁面執行 **Update rankings and publish**；第一次完成後，網站網址會出現在 Pages 設定和工作流程摘要中。

工作流程會在台灣時間平日約 17:00 執行，下載最近 77 個兩市場都有行情的交易日，產生 `site/data/rankings.json`，並用 GitHub Actions 的短效 repository token 將新排行提交回 repository 後部署。此 token 由 GitHub 執行時提供；原始碼沒有個人存取 token 或其他憑證。

## 排名規則

- 15 項技術訊號，每項 10 分；至少命中 8 項（80 分）。
- 收盤價需高於 10 元，成交量至少 2,000 張。
- 先按分數由高至低排序；同分時按成交量由高至低排序。
- 首頁顯示最新交易日前三名，下方顯示之前六個交易日各自的前三名。
- 訊號計算與 Android 版一致；資料使用未還原 OHLCV，這個排行不是投資建議。

## 隱私與公開檔案

網站只會發佈公開市場排行。更新程式使用 Python 標準函式庫，不讀取本機環境變數、個人檔案或 FinLab 設定。專案根目錄的 `.gitignore` 也排除 Android 專案、舊 `text1.py`、SQLite 資料庫與舊 `requirements.txt`。網站程式位於 `site/`、`scripts/` 和 `.github/workflows/`。

市場資料來源：

- [臺灣證券交易所盤後行情](https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX)
- [證券櫃檯買賣中心歷史行情](https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes)
- [櫃買中心 OpenAPI](https://www.tpex.org.tw/openapi/)


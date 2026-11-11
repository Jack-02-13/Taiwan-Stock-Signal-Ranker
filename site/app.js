const statusNode = document.querySelector("#data-status");
const dateNode = document.querySelector("#latest-date");
const latestTitleNode = document.querySelector("#latest-title");
const summaryNode = document.querySelector("#latest-summary");
const latestList = document.querySelector("#latest-list");
const historyGrid = document.querySelector("#history-grid");
const refreshButton = document.querySelector("#refresh-button");
const themeOptions = [...document.querySelectorAll("[data-theme-choice]")];
const methodLink = document.querySelector("#method-link");
const disclaimerLink = document.querySelector("#disclaimer-link");
const modalOverlay = document.querySelector("#modal-overlay");
const modalCard = document.querySelector("#modal-card");
const modalContent = document.querySelector("#modal-content");
const pullRefreshIndicator = document.querySelector("#pull-refresh-indicator");
const themeColorMeta = document.querySelector('meta[name="theme-color"]');
const RANKINGS_API_URL = "https://taiwan-stock-ranker-market-data.jack-0213.workers.dev/api/rankings";
const THEME_STORAGE_KEY = "taiwan-stock-ranker-theme";

const numberFormat = new Intl.NumberFormat("zh-TW", { maximumFractionDigits: 2 });
let retryTimer = null;
let isLoading = false;
let pullTracking = false;
let pullStartY = 0;
let pullRefreshRequested = false;
let previousFocus = null;

function applyTheme(theme, persist = false) {
  const selectedTheme = theme === "dark" ? "dark" : "light";
  document.documentElement.dataset.theme = selectedTheme;
  themeColorMeta?.setAttribute("content", selectedTheme === "dark" ? "#12171c" : "#f4f6f5");
  for (const option of themeOptions) {
    option.setAttribute("aria-pressed", String(option.dataset.themeChoice === selectedTheme));
  }
  if (persist) {
    try {
      localStorage.setItem(THEME_STORAGE_KEY, selectedTheme);
    } catch (error) {
      console.info("Theme preference could not be saved", error);
    }
  }
}

function initializeTheme() {
  let savedTheme = "light";
  try {
    savedTheme = localStorage.getItem(THEME_STORAGE_KEY) || "light";
  } catch (error) {
    console.info("Theme preference is unavailable", error);
  }
  applyTheme(savedTheme);
  for (const option of themeOptions) {
    option.addEventListener("click", () => applyTheme(option.dataset.themeChoice, true));
  }
}

function formatDate(isoDate) {
  if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return "尚無資料";
  const [year, month, day] = isoDate.split("-");
  return `${year}/${month}/${day}`;
}

function makeEmpty(message, className = "empty-state") {
  const node = document.createElement("p");
  node.className = className;
  node.textContent = message;
  return node;
}

function makeModalHeading(title) {
  const heading = document.createElement("h2");
  heading.className = "modal-title";
  heading.id = "modal-title";
  heading.textContent = title;
  return heading;
}

function makeSectionHeading(text) {
  const heading = document.createElement("h3");
  heading.className = "modal-section-title";
  heading.textContent = text;
  return heading;
}

function makeModalText(text, className = "modal-text") {
  const node = document.createElement("p");
  node.className = className;
  node.textContent = text;
  return node;
}

function openModal(buildContent) {
  if (!modalOverlay.hidden) return;
  modalContent.replaceChildren();
  buildContent(modalContent);
  previousFocus = document.activeElement;
  modalOverlay.hidden = false;
  document.body.classList.add("modal-open");
  requestAnimationFrame(() => {
    modalOverlay.classList.add("is-open");
    modalCard.focus({ preventScroll: true });
  });
}

function closeModal() {
  if (modalOverlay.hidden) return;
  modalOverlay.classList.remove("is-open");
  document.body.classList.remove("modal-open");
  window.setTimeout(() => {
    modalOverlay.hidden = true;
    modalContent.replaceChildren();
    if (previousFocus instanceof HTMLElement) previousFocus.focus({ preventScroll: true });
  }, 170);
}

function openPickDetails(stock, index) {
  const signals = Array.isArray(stock.signals) ? stock.signals : [];
  const stockName = stock.name || stock.symbol || "—";
  openModal((content) => {
    const headingRow = document.createElement("div");
    headingRow.className = "modal-stock-heading";
    const titleStack = document.createElement("div");
    const title = makeModalHeading(stockName);
    const subtitle = makeModalText(`${stock.symbol || ""} · 第 ${index + 1} 名`, "modal-subtitle");
    titleStack.append(title, subtitle);
    const score = document.createElement("span");
    score.className = "modal-score";
    score.textContent = `${Number(stock.score) || 0}/150`;
    headingRow.append(titleStack, score);
    content.append(headingRow);

    content.append(makeModalText(
      `收盤 ${numberFormat.format(Number(stock.close) || 0)} 元  ·  成交 ${numberFormat.format(Number(stock.volumeLots) || 0)} 張`,
      "modal-market-summary"
    ));

    const divider = document.createElement("div");
    divider.className = "modal-divider";
    content.append(divider);

    const conditionHeader = document.createElement("div");
    conditionHeader.className = "modal-condition-heading";
    conditionHeader.append(makeSectionHeading("達成條件"));
    const count = document.createElement("span");
    count.className = "modal-condition-count";
    count.textContent = `${signals.length} 項`;
    conditionHeader.append(count);
    content.append(conditionHeader);

    if (!signals.length) {
      content.append(makeModalText("目前沒有達成條件。", "modal-empty"));
      return;
    }

    const list = document.createElement("ul");
    list.className = "condition-list modal-scroll";
    for (const signal of signals) {
      const item = document.createElement("li");
      const check = document.createElement("span");
      check.className = "condition-check";
      check.setAttribute("aria-hidden", "true");
      check.textContent = "✓";
      const text = document.createElement("span");
      text.textContent = String(signal);
      item.append(check, text);
      list.append(item);
    }
    content.append(list);
  });
}

const METHOD_RULES = [
  ["收盤高於 20 日線", "當日收盤價 > 20 日平均收盤價"],
  ["20 日線高於 60 日線", "20 日均線 > 60 日均線"],
  ["60 日線上彎", "目前 60 日均線 > 10 個交易日前的 60 日均線"],
  ["20 日線上彎", "目前 20 日均線 > 5 個交易日前的 20 日均線"],
  ["MACD 多方", "MACD 線（EMA12 − EMA26）> 訊號線"],
  ["MACD 動能增強", "今日 MACD 柱狀體 > 前一交易日"],
  ["RSI 50 至 70", "14 日 RSI 介於 50 到 70，含上下限"],
  ["KD 多方且未過熱", "K 值 > D 值，且 K 值 < 80"],
  ["突破 20 日高點", "收盤價 > 前 20 個交易日最高價"],
  ["突破 60 日高點", "收盤價 > 前 60 個交易日最高價"],
  ["成交量放大", "今日成交量 > 前 20 日均量的 1.2 倍"],
  ["OBV 偏多", "今日 OBV > 近 20 個交易日 OBV 均值"],
  ["MFI 偏多", "14 日 MFI > 50"],
  ["ADX 趨勢偏多", "ADX > 20，且 +DI > −DI"],
  ["20 日報酬為正", "今日收盤價 > 20 個交易日前收盤價"],
];

function openMethodDetails() {
  openModal((content) => {
    content.append(makeModalHeading("詳細資料"));
    content.append(makeModalText("15 項技術指標，每符合 1 項加 10 分，最高 150 分。", "modal-intro"));

    const list = document.createElement("div");
    list.className = "method-scroll modal-scroll";
    list.append(makeSectionHeading("入選門檻"));
    const thresholds = [
      "至少符合 8 項條件（80 分）",
      "收盤價高於 10 元",
      "成交量至少 2,000 張",
      "至少有 70 個交易日資料才會計算",
      "符合門檻後取分數前三名；同分時成交量較大者優先",
      "只使用證交所上市股票行情",
    ];
    const bullets = document.createElement("ul");
    bullets.className = "method-bullets";
    for (const threshold of thresholds) {
      const item = document.createElement("li");
      item.textContent = threshold;
      bullets.append(item);
    }
    list.append(bullets, makeSectionHeading("15 項計分條件（每項 10 分）"));

    const rules = document.createElement("ol");
    rules.className = "method-rules";
    for (const [name, formula] of METHOD_RULES) {
      const item = document.createElement("li");
      const textStack = document.createElement("div");
      const ruleName = document.createElement("strong");
      ruleName.textContent = `${name} · 10 分`;
      const ruleFormula = document.createElement("span");
      ruleFormula.textContent = formula;
      textStack.append(ruleName, ruleFormula);
      item.append(textStack);
      rules.append(item);
    }
    list.append(rules);
    content.append(list);
  });
}

function openDisclaimer() {
  openModal((content) => {
    content.append(makeModalHeading("免責聲明"));
    content.append(makeModalText(
      "本頁依公開行情與技術條件產生排名，僅供資訊參考，不保證獲利。買賣決策及投資風險由使用者自行評估並承擔。",
      "disclaimer-text"
    ));
  });
}

function makeStockRow(stock, index) {
  const card = document.createElement("article");
  card.className = `rank-card${index === 0 ? " is-first" : ""}`;
  card.tabIndex = 0;
  card.setAttribute("role", "button");
  card.setAttribute("aria-label", `查看第 ${index + 1} 名 ${stock.name || stock.symbol || "股票"} 的達成條件`);
  card.addEventListener("click", () => openPickDetails(stock, index));
  card.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openPickDetails(stock, index);
    }
  });

  const main = document.createElement("div");
  main.className = "rank-main";
  const rank = document.createElement("span");
  rank.className = "rank-number";
  rank.textContent = String(index + 1).padStart(2, "0");
  main.append(rank);

  const heading = document.createElement("div");
  heading.className = "stock-heading";
  const symbol = document.createElement("div");
  symbol.className = "stock-symbol";
  symbol.textContent = stock.symbol || "";
  const name = document.createElement("div");
  name.className = "stock-name";
  name.textContent = stock.name || stock.symbol || "—";
  heading.append(symbol, name);
  main.append(heading);

  const score = document.createElement("span");
  score.className = "score-pill";
  score.textContent = `${Number(stock.score) || 0} 分`;
  main.append(score);
  card.append(main);

  const stats = document.createElement("div");
  stats.className = "rank-stats";
  const price = document.createElement("span");
  price.innerHTML = "收盤 <b></b>";
  price.querySelector("b").textContent = numberFormat.format(Number(stock.close) || 0);
  const volume = document.createElement("span");
  volume.innerHTML = "成交 <b></b> 張";
  volume.querySelector("b").textContent = numberFormat.format(Number(stock.volumeLots) || 0);
  stats.append(price, volume);
  card.append(stats);
  return card;
}

function renderDayRanking(container, day, isLatest = false) {
  container.replaceChildren();
  const picks = Array.isArray(day?.picks) ? day.picks.slice() : [];
  picks.sort((a, b) => (Number(b.score) - Number(a.score))
    || (Number(b.volumeLots) - Number(a.volumeLots))
    || String(a.symbol).localeCompare(String(b.symbol), "en"));
  picks.slice(0, 3).forEach((stock, index) => container.append(makeStockRow(stock, index)));
  if (!picks.length) {
    container.append(makeEmpty(isLatest ? "此交易日沒有股票達到入選門檻。" : "當日沒有股票達到入選門檻。", isLatest ? "empty-state" : "history-empty"));
  }
}

function makeHistoryCard(day, index) {
  const card = document.createElement("article");
  card.className = "history-card";
  const top = document.createElement("div");
  top.className = "history-topline";
  const date = document.createElement("h3");
  date.className = "history-date";
  date.textContent = day ? formatDate(day.date) : `第 ${index + 1} 天`;
  top.append(date);
  card.append(top);

  if (!day) {
    card.append(makeEmpty("尚無資料", "history-empty"));
    return card;
  }

  const picks = Array.isArray(day.picks) ? day.picks.slice() : [];
  picks.sort((a, b) => (Number(b.score) - Number(a.score))
    || (Number(b.volumeLots) - Number(a.volumeLots))
    || String(a.symbol).localeCompare(String(b.symbol), "en"));
  if (!picks.length) {
    card.append(makeEmpty("當日無符合股票", "history-empty"));
    return card;
  }

  const list = document.createElement("ol");
  list.className = "history-name-list";
  for (const [rank, stock] of picks.slice(0, 3).entries()) {
    const item = document.createElement("li");
    const number = document.createElement("span");
    number.className = "history-name-rank";
    number.textContent = `${rank + 1}.`;
    const name = document.createElement("span");
    name.className = "history-stock-name";
    name.textContent = stock.name || "—";
    item.append(number, name);
    list.append(item);
  }
  card.append(list);
  return card;
}

function render(data) {
  const rankings = Array.isArray(data.rankings) ? data.rankings.slice() : [];
  rankings.sort((a, b) => String(b.date).localeCompare(String(a.date)));
  const latest = rankings[0];

  if (!latest) {
    latestTitleNode.textContent = "尚無排行資料";
    dateNode.textContent = "尚無資料";
    summaryNode.textContent = "排行資料目前尚未產生。";
    renderDayRanking(latestList, null, true);
    historyGrid.replaceChildren(...Array.from({ length: 6 }, (_, i) => makeHistoryCard(null, i)));
    statusNode.textContent = "尚未有排行資料";
    return;
  }

  latestTitleNode.textContent = `${formatDate(latest.date)} 前三名`;
  dateNode.textContent = formatDate(latest.date);
  summaryNode.textContent = `${Number(latest.qualifiedCount) || 0} 檔符合條件 · 分數優先，同分按成交量排序`;
  renderDayRanking(latestList, latest, true);
  historyGrid.replaceChildren(...Array.from({ length: 6 }, (_, i) => makeHistoryCard(rankings[i + 1], i)));
  statusNode.textContent = `排行截至 ${formatDate(data.latestDate || latest.date)} · 已取 ${Number(data.historySessionCount) || rankings.length} 個交易日資料`;
}

function beginPullRefresh() {
  pullRefreshRequested = true;
  pullRefreshIndicator.classList.add("is-refreshing");
  pullRefreshIndicator.setAttribute("aria-hidden", "false");
  loadRankings({ fromPull: true });
}

async function loadRankings({ fromPull = false } = {}) {
  if (isLoading) return;
  isLoading = true;
  refreshButton.disabled = true;
  refreshButton.classList.add("is-loading");
  refreshButton.querySelector(".refresh-label").textContent = "更新中";
  statusNode.textContent = "檢查資料日期並載入最新排行…";
  let usedWorker = false;
  try {
    const apiUrl = new URL(RANKINGS_API_URL);
    apiUrl.searchParams.set("refresh", String(Date.now()));
    const response = await fetch(apiUrl, {
      cache: "no-store",
      headers: { "Cache-Control": "no-cache" },
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    render(data);
    usedWorker = true;
    statusNode.textContent = data.marketDataStatus === "current"
      ? `已取得今日盤後排行 · ${dateNode.textContent}`
      : `已載入最近交易日排行 · ${dateNode.textContent}`;
  } catch (workerError) {
    console.warn("Unable to refresh rankings from the data service", workerError);
    try {
      const rankingsUrl = new URL("./data/rankings.json", window.location.href);
      rankingsUrl.searchParams.set("refresh", String(Date.now()));
      const response = await fetch(rankingsUrl, {
        cache: "no-store",
        headers: { "Cache-Control": "no-cache" },
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      render(await response.json());
      statusNode.textContent = isTaipeiAfterClose() && getRenderedDate() === getTaipeiDate()
        ? `已載入今日盤後排行 · ${dateNode.textContent}`
        : `目前顯示最近交易日排行 · ${dateNode.textContent}`;
    } catch (error) {
      statusNode.textContent = `更新排行失敗 · 目前顯示 ${dateNode.textContent} 的資料`;
      console.warn("Unable to load published rankings", error);
    }
  } finally {
    isLoading = false;
    refreshButton.disabled = false;
    refreshButton.classList.remove("is-loading");
    refreshButton.querySelector(".refresh-label").textContent = "更新資料";
    if (pullRefreshRequested || fromPull) {
      pullRefreshRequested = false;
      pullRefreshIndicator.classList.remove("is-visible", "is-refreshing", "is-ready");
      pullRefreshIndicator.setAttribute("aria-hidden", "true");
      pullRefreshIndicator.style.removeProperty("--pull-progress");
    }
    if (retryTimer) clearTimeout(retryTimer);
    if (isTaipeiAfterClose() && getRenderedDate() !== getTaipeiDate()) {
      retryTimer = setTimeout(() => loadRankings(), 5 * 60 * 1000);
      if (!usedWorker) statusNode.textContent = `今日盤後排行尚未更新 · 目前顯示最近交易日 ${dateNode.textContent}`;
    }
  }
}

function getTaipeiParts() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Taipei", year: "numeric", month: "2-digit", day: "2-digit", weekday: "short",
  }).formatToParts(new Date());
  return Object.fromEntries(parts.map((part) => [part.type, part.value]));
}

function getTaipeiDate() {
  const parts = getTaipeiParts();
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function isTaipeiAfterClose() {
  const parts = getTaipeiParts();
  const isWeekday = !["Sat", "Sun"].includes(parts.weekday);
  const hour = Number(new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Taipei", hour: "2-digit", hourCycle: "h23",
  }).format(new Date()));
  return isWeekday && hour >= 14;
}

function getRenderedDate() {
  const match = /^(\d{4})\/(\d{2})\/(\d{2})$/.exec(dateNode.textContent.trim());
  return match ? `${match[1]}-${match[2]}-${match[3]}` : "";
}

function startPullGesture(event) {
  if (window.scrollY > 0 || !event.touches || event.touches.length !== 1 || !modalOverlay.hidden) return;
  if (event.target instanceof Element && event.target.closest("button, a, input, select, textarea")) return;
  pullStartY = event.touches[0].clientY;
  pullTracking = true;
}

function trackPullGesture(event) {
  if (!pullTracking || !event.touches || event.touches.length !== 1) return;
  const distance = event.touches[0].clientY - pullStartY;
  if (window.scrollY > 0 || distance <= 0) return;
  if (event.cancelable) event.preventDefault();
  const progress = Math.min(distance / 58, 1);
  pullRefreshIndicator.style.setProperty("--pull-progress", String(progress));
  pullRefreshIndicator.classList.add("is-visible");
  if (progress >= 1) pullRefreshIndicator.classList.add("is-ready");
  if (distance >= 58 && !pullRefreshRequested && !isLoading) beginPullRefresh();
}

function finishPullGesture() {
  if (!pullTracking) return;
  pullTracking = false;
  if (!pullRefreshRequested) {
    pullRefreshIndicator.classList.remove("is-visible", "is-ready");
    pullRefreshIndicator.setAttribute("aria-hidden", "true");
    pullRefreshIndicator.style.removeProperty("--pull-progress");
  }
}

refreshButton.addEventListener("click", () => loadRankings());
methodLink.addEventListener("click", openMethodDetails);
disclaimerLink.addEventListener("click", openDisclaimer);
modalOverlay.addEventListener("click", (event) => {
  if (event.target === modalOverlay) closeModal();
});
document.addEventListener("keydown", (event) => {
  if (modalOverlay.hidden) return;
  if (event.key === "Escape") {
    closeModal();
  } else if (event.key === "Tab") {
    event.preventDefault();
    modalCard.focus();
  }
});
window.addEventListener("touchstart", startPullGesture, { passive: true });
window.addEventListener("touchmove", trackPullGesture, { passive: false });
window.addEventListener("touchend", finishPullGesture, { passive: true });
window.addEventListener("touchcancel", finishPullGesture, { passive: true });

initializeTheme();
loadRankings();

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("./service-worker.js").catch((error) => {
      console.info("Offline cache is unavailable", error);
    });
  });
}

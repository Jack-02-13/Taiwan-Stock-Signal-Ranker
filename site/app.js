const statusNode = document.querySelector("#data-status");
const dateNode = document.querySelector("#latest-date");
const latestTitleNode = document.querySelector("#latest-title");
const summaryNode = document.querySelector("#latest-summary");
const latestList = document.querySelector("#latest-list");
const historyGrid = document.querySelector("#history-grid");
const updatedNode = document.querySelector("#updated-at");
const refreshButton = document.querySelector("#refresh-button");
const RANKINGS_API_URL = "https://taiwan-stock-ranker-market-data.jack-0213.workers.dev/api/rankings";

const numberFormat = new Intl.NumberFormat("zh-TW", { maximumFractionDigits: 2 });
let retryTimer = null;

function formatDate(isoDate) {
  if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return "尚無資料";
  const [year, month, day] = isoDate.split("-");
  return `${year}/${month}/${day}`;
}

function formatUpdatedAt(value) {
  if (!value) return "尚未產生排行資料；請先執行 GitHub Actions 更新流程。";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return `資料計算時間：${new Intl.DateTimeFormat("zh-TW", {
    timeZone: "Asia/Taipei",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date)}`;
}

function makeEmpty(message, className = "empty-state") {
  const node = document.createElement("p");
  node.className = className;
  node.textContent = message;
  return node;
}

function makeStockRow(stock, index, compact = false) {
  const card = document.createElement("article");
  card.className = `rank-card${index === 0 ? " is-first" : ""}`;

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

  const signals = Array.isArray(stock.signals) ? stock.signals : [];
  if (signals.length) {
    const details = document.createElement("details");
    details.className = "signal-details";
    const summary = document.createElement("summary");
    summary.textContent = `命中 ${signals.length} / 15 項訊號`;
    const list = document.createElement("ul");
    for (const signal of signals) {
      const item = document.createElement("li");
      item.textContent = String(signal);
      list.append(item);
    }
    details.append(summary, list);
    card.append(details);
  }

  return card;
}

function renderDayRanking(container, day, isLatest = false) {
  container.replaceChildren();
  const picks = Array.isArray(day?.picks) ? day.picks.slice() : [];
  picks.sort((a, b) => (Number(b.score) - Number(a.score)) || (Number(b.volumeLots) - Number(a.volumeLots)));
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

  if (day) {
    const picks = Array.isArray(day.picks) ? day.picks.slice() : [];
    picks.sort((a, b) => (Number(b.score) - Number(a.score)) || (Number(b.volumeLots) - Number(a.volumeLots)));
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
    if (picks.length) {
      card.append(list);
    } else {
      card.append(makeEmpty("當日無符合股票", "history-empty"));
    }
  } else {
    card.append(makeEmpty("尚無資料", "history-empty"));
  }
  return card;
}

function render(data) {
  const rankings = Array.isArray(data.rankings) ? data.rankings.slice() : [];
  rankings.sort((a, b) => String(b.date).localeCompare(String(a.date)));
  const latest = rankings[0];

  if (!latest) {
    latestTitleNode.textContent = "尚無排行資料";
    dateNode.textContent = "尚無資料";
    summaryNode.textContent = "排行會在 GitHub Actions 首次成功更新後顯示。";
    renderDayRanking(latestList, null, true);
    historyGrid.replaceChildren(...Array.from({ length: 6 }, (_, i) => makeHistoryCard(null, i)));
    statusNode.textContent = "尚未有排行資料";
    updatedNode.textContent = formatUpdatedAt(data.generatedAt);
    return;
  }

  latestTitleNode.textContent = `${formatDate(latest.date)} 前三名`;
  dateNode.textContent = formatDate(latest.date);
  summaryNode.textContent = `${Number(latest.qualifiedCount) || 0} 檔符合條件 · 分數優先，同分按成交量排序`;
  renderDayRanking(latestList, latest, true);
  historyGrid.replaceChildren(...Array.from({ length: 6 }, (_, i) => makeHistoryCard(rankings[i + 1], i)));
  statusNode.textContent = `排行截至 ${formatDate(data.latestDate || latest.date)} · 已取 ${Number(data.historySessionCount) || rankings.length} 個交易日資料`;
  updatedNode.textContent = formatUpdatedAt(data.generatedAt);
}

async function loadRankings() {
  refreshButton.disabled = true;
  refreshButton.querySelector(".refresh-label").textContent = "載入中";
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
    if (data.marketDataStatus === "current") {
      statusNode.textContent = `已取得今日盤後排行 · ${dateNode.textContent}`;
    } else {
      statusNode.textContent = `已載入最近交易日排行 · ${dateNode.textContent}`;
    }
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
    refreshButton.disabled = false;
    refreshButton.querySelector(".refresh-label").textContent = "更新";
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
  const text = dateNode.textContent.trim();
  const match = /^(\d{4})\/(\d{2})\/(\d{2})$/.exec(text);
  return match ? `${match[1]}-${match[2]}-${match[3]}` : "";
}

refreshButton.addEventListener("click", loadRankings);
loadRankings();

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("./service-worker.js").catch((error) => {
      console.info("Offline cache is unavailable", error);
    });
  });
}


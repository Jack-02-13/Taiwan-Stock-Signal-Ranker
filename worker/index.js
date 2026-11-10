import { calculateLatestRanking } from "./ranking.js";

const SITE_ORIGINS = new Set([
  "https://jack-02-13.github.io",
  "http://localhost:8000",
  "http://127.0.0.1:8000",
  "http://localhost:8788",
  "http://127.0.0.1:8788",
]);

const MAX_HISTORY_DAYS = 180;
const RECENT_DAY_REFRESH_MINUTES = 30;
const PUBLISHED_RANKINGS_URL = "https://jack-02-13.github.io/Taiwan-Stock-Signal-Ranker/data/rankings.json";
const MARKET_HISTORY_URL = "https://jack-02-13.github.io/Taiwan-Stock-Signal-Ranker/data/market-history.json";
const recentRequests = new Map();

export default {
  async scheduled(_event, env) {
    const date = getTaipeiDate();
    if (!isTaipeiWeekday(date) || !isAfterTaipeiTime(14, 0) || !env.MARKET_DATA) return;
    let changed = false;
    for (const market of ["TWSE", "TPEX"]) {
      const cached = await readStored(env.MARKET_DATA, market, date);
      const ageMinutes = cached ? (Date.now() - Date.parse(cached.fetchedAt)) / 60000 : Number.POSITIVE_INFINITY;
      if (cached && ageMinutes < RECENT_DAY_REFRESH_MINUTES) continue;
      const failKey = `retry:${market}:${date}`;
      const failedUntil = await env.MARKET_DATA.get(failKey);
      if (failedUntil && Date.now() < Number(failedUntil)) continue;
      try {
        await fetchAndStore(env.MARKET_DATA, market, date, true);
        changed = true;
      } catch (_error) {
        await env.MARKET_DATA.put(failKey, String(Date.now() + 5 * 60 * 1000), { expirationTtl: 300 });
      }
    }
    if (changed) await env.MARKET_DATA.delete(`rankings:${date}`);
  },

  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin") || "";
    const corsHeaders = makeCorsHeaders(origin);

    if (request.method === "OPTIONS") {
      if (!SITE_ORIGINS.has(origin)) return new Response(null, { status: 403 });
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405, corsHeaders);
    if (!["/api/market", "/api/rankings"].includes(url.pathname)) return json({ error: "not_found" }, 404, corsHeaders);
    if (!SITE_ORIGINS.has(origin)) return json({ error: "origin_not_allowed" }, 403);
    if (!env.MARKET_DATA) return json({ error: "storage_not_configured" }, 503, corsHeaders);

    if (url.pathname === "/api/rankings") {
      try {
        const result = await getRankings(env.MARKET_DATA);
        return json(result, 200, corsHeaders);
      } catch (error) {
        return json({ error: "rankings_unavailable", message: safeMessage(error) }, 503, corsHeaders);
      }
    }

    const market = url.searchParams.get("market");
    const date = url.searchParams.get("date");
    const forceRefresh = url.searchParams.get("refresh") === "1";
    const parsedDate = parseIsoDate(date);
    if (!new Set(["TWSE", "TPEX"]).has(market) || !parsedDate) {
      return json({ error: "invalid_market_or_date" }, 400, corsHeaders);
    }

    const taipeiToday = getTaipeiDate();
    const dayOffset = Math.floor((Date.parse(`${taipeiToday}T00:00:00Z`) - Date.parse(`${date}T00:00:00Z`)) / 86400000);
    if (dayOffset < 0 || dayOffset >= MAX_HISTORY_DAYS) {
      return json({ error: "date_out_of_range" }, 400, corsHeaders);
    }

    const isToday = date === taipeiToday;
    const isRecent = dayOffset < 5;
    if (isToday && (!isTaipeiWeekday(date) || !isAfterTaipeiTime(14, 0))) {
      const cached = await readStored(env.MARKET_DATA, market, date);
      if (cached) return json(cached, 200, corsHeaders);
      return json({ error: "market_data_not_ready", retryAfterSeconds: 900 }, 425, corsHeaders);
    }

    const cached = await readStored(env.MARKET_DATA, market, date);
    const cacheAgeMinutes = cached
      ? (Date.now() - Date.parse(cached.fetchedAt)) / 60000
      : Number.POSITIVE_INFINITY;
    const shouldFetch = !cached || (forceRefresh && isRecent && cacheAgeMinutes >= RECENT_DAY_REFRESH_MINUTES);

    if (!shouldFetch) return json(cached, 200, corsHeaders);

    const failKey = `retry:${market}:${date}`;
    const failedUntil = await env.MARKET_DATA.get(failKey);
    if (failedUntil && Date.now() < Number(failedUntil)) {
      return cached
        ? json(cached, 200, { ...corsHeaders, "X-Market-Data-Stale": "1" })
        : json({ error: "upstream_temporarily_unavailable", retryAfterSeconds: 300 }, 503, corsHeaders);
    }

    const lockKey = `${market}:${date}`;
    const pending = recentRequests.get(lockKey);
    if (pending) {
      const result = await pending;
      return json(result, 200, corsHeaders);
    }

    const work = fetchAndStore(env.MARKET_DATA, market, date, isToday)
      .catch(async (error) => {
        await env.MARKET_DATA.put(failKey, String(Date.now() + 5 * 60 * 1000), { expirationTtl: 300 });
        if (cached) return { ...cached, stale: true };
        throw error;
      })
      .finally(() => recentRequests.delete(lockKey));
    recentRequests.set(lockKey, work);

    try {
      const result = await work;
      return json(result, 200, result.stale ? { ...corsHeaders, "X-Market-Data-Stale": "1" } : corsHeaders);
    } catch (error) {
      return json({ error: "upstream_unavailable", message: safeMessage(error) }, 503, corsHeaders);
    }
  },
};

function makeCorsHeaders(origin) {
  if (!SITE_ORIGINS.has(origin)) return { "Cache-Control": "no-store" };
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Accept, Content-Type",
    "Access-Control-Max-Age": "86400",
    "Cache-Control": "no-store",
    Vary: "Origin",
  };
}

function json(value, status, headers = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...headers },
  });
}

async function getRankings(namespace) {
  const today = getTaipeiDate();
  const afterClose = isTaipeiWeekday(today) && isAfterTaipeiTime(14, 0);
  const published = await fetchPublishedJson(PUBLISHED_RANKINGS_URL);
  if (!Array.isArray(published.rankings)) throw new Error("公開排行資料格式錯誤。");

  if (!afterClose) return { ...published, marketDataStatus: "published" };
  if (published.latestDate === today) return { ...published, marketDataStatus: "current" };

  const cacheKey = `rankings:${today}`;
  const cachedRanking = await namespace.get(cacheKey, { type: "json" });
  if (cachedRanking?.data?.latestDate === today) return cachedRanking.data;

  const history = await fetchPublishedJson(MARKET_HISTORY_URL);
  if (!Array.isArray(history.dates) || history.dates.length < 76 || !Array.isArray(history.stocks)) {
    throw new Error("歷史行情尚未部署完成，暫時無法計算今日排行。");
  }

  let currentBars = [];
  if (!history.dates.includes(today)) {
    currentBars = (await Promise.all(["TWSE", "TPEX"].map((market) => getTodayMarketBars(namespace, market, today)))).flat();
  }

  const latest = calculateLatestRanking(history, currentBars, today);
  const olderRankings = published.rankings
    .filter((day) => day?.date !== today)
    .sort((left, right) => String(right.date).localeCompare(String(left.date)))
    .slice(0, 6);
  const result = {
    schemaVersion: published.schemaVersion || 1,
    generatedAt: new Date().toISOString(),
    latestDate: today,
    historySessionCount: 77,
    sources: published.sources || [
      "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX",
      "https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes",
    ],
    method: published.method,
    rankings: [latest, ...olderRankings],
    marketDataStatus: "current",
  };
  await namespace.put(cacheKey, JSON.stringify({ data: result, calculatedAt: new Date().toISOString() }), {
    expirationTtl: 60 * 60 * 48,
  });
  return result;
}

async function getTodayMarketBars(namespace, market, date) {
  const cached = await readStored(namespace, market, date);
  const ageMinutes = cached ? (Date.now() - Date.parse(cached.fetchedAt)) / 60000 : Number.POSITIVE_INFINITY;
  if (cached && ageMinutes < RECENT_DAY_REFRESH_MINUTES) return cached.bars;

  const failKey = `retry:${market}:${date}`;
  const failedUntil = await namespace.get(failKey);
  if (cached && failedUntil && Date.now() < Number(failedUntil)) return cached.bars;
  try {
    return (await fetchAndStore(namespace, market, date, true)).bars;
  } catch (error) {
    await namespace.put(failKey, String(Date.now() + 5 * 60 * 1000), { expirationTtl: 300 });
    if (cached) return cached.bars;
    throw error;
  }
}

async function fetchPublishedJson(address) {
  const response = await fetch(address, {
    headers: { Accept: "application/json", "Cache-Control": "no-cache" },
    cf: { cacheTtl: 0, cacheEverything: false },
  });
  if (!response.ok) throw new Error(`公開資料 HTTP ${response.status}`);
  return response.json();
}

function parseIsoDate(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? null : value;
}

function getTaipeiDate() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Taipei", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function isTaipeiWeekday(isoDate) {
  const weekday = new Date(`${isoDate}T00:00:00Z`).getUTCDay();
  return weekday >= 1 && weekday <= 5;
}

function isAfterTaipeiTime(hour, minute) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Taipei", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  const currentMinutes = Number(values.hour) * 60 + Number(values.minute);
  return currentMinutes >= hour * 60 + minute;
}

async function readStored(namespace, market, date) {
  return namespace.get(`bars:${market}:${date}`, { type: "json" });
}

async function fetchAndStore(namespace, market, date, isToday) {
  const payload = await fetchMarketPayload(market, date);
  const responseDate = String(payload.date || "").replaceAll("-", "").replaceAll("/", "");
  const requestedDate = date.replaceAll("-", "");
  if (responseDate && responseDate !== requestedDate) {
    throw new Error("交易所回傳日期與要求日期不同，已保留舊資料。");
  }

  const parsed = parseBars(payload, market, date);
  if (!parsed.hasQuoteTable) throw new Error("交易所行情格式暫時無法辨識。");
  if (isToday && parsed.bars.length === 0) {
    throw new Error("今日盤後行情尚未發布，稍後會再試。");
  }
  if (isToday && parsed.bars.length < 300) {
    throw new Error("今日盤後行情筆數不足，暫不覆蓋已保存資料。");
  }

  const record = {
    date,
    market,
    fetchedAt: new Date().toISOString(),
    bars: parsed.bars,
    noTradingData: parsed.bars.length === 0,
  };
  await namespace.put(`bars:${market}:${date}`, JSON.stringify(record), {
    expirationTtl: parsed.bars.length === 0 ? 60 * 60 * 24 * 30 : 60 * 60 * 24 * 365,
  });
  return record;
}

async function fetchMarketPayload(market, date) {
  const [year, month, day] = date.split("-");
  let address;
  let referer;
  if (market === "TWSE") {
    const query = new URLSearchParams({ date: `${year}${month}${day}`, type: "ALLBUT0999", response: "json" });
    address = `https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?${query}`;
    referer = "https://www.twse.com.tw/";
  } else {
    const query = new URLSearchParams({ response: "json", date: `${year}/${month}/${day}` });
    address = `https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?${query}`;
    referer = "https://www.tpex.org.tw/";
  }

  const response = await fetch(address, {
    headers: {
      Accept: "application/json,text/plain,*/*",
      "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.7",
      Referer: referer,
      "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
    },
    cf: { cacheTtl: 0, cacheEverything: false },
  });
  if (!response.ok) throw new Error(`${market} HTTP ${response.status}`);
  return response.json();
}

function parseBars(payload, market, date) {
  const tables = Array.isArray(payload?.tables) ? payload.tables : [payload];
  const bars = [];
  let hasQuoteTable = false;
  for (const table of tables) {
    if (!table || typeof table !== "object") continue;
    const fields = firstArray(table, "fields", "Field", "field", "headers");
    const rows = firstArray(table, "data", "aaData", "rows");
    if (!fields || !rows) continue;

    const codeIndex = findHeader(fields, ["證券代號", "股票代號", "代號", "SecuritiesCompanyCode", "Code"]);
    const nameIndex = findHeader(fields, ["證券名稱", "股票名稱", "名稱", "Name"]);
    const openIndex = findHeader(fields, ["開盤價", "開盤", "Open"]);
    const highIndex = findHeader(fields, ["最高價", "最高", "High"]);
    const lowIndex = findHeader(fields, ["最低價", "最低", "Low"]);
    const closeIndex = findHeader(fields, ["收盤價", "收盤", "Close"]);
    const volumeIndex = findHeader(fields, ["成交股數", "成交量(股)", "成交量", "TradingShares", "Volume"]);
    if (codeIndex < 0 || closeIndex < 0 || volumeIndex < 0) continue;
    hasQuoteTable = true;

    for (const entry of rows) {
      const row = Array.isArray(entry) ? entry : entry?.value;
      if (!Array.isArray(row) || row.length <= Math.max(codeIndex, closeIndex, volumeIndex)) continue;
      const symbol = cell(row, codeIndex);
      const name = cell(row, nameIndex) || symbol;
      if (!isCommonStock(symbol, name)) continue;
      const close = parseNumber(cell(row, closeIndex));
      const volume = parseNumber(cell(row, volumeIndex));
      if (close === null || volume === null || close <= 0 || volume <= 0) continue;
      const open = parseNumber(cell(row, openIndex)) ?? close;
      const high = parseNumber(cell(row, highIndex)) ?? Math.max(open, close);
      const low = parseNumber(cell(row, lowIndex)) ?? Math.min(open, close);
      bars.push({ symbol, name, market, date, open, high, low, close, volume });
    }
  }

  return { bars, hasQuoteTable };
}

function firstArray(object, ...keys) {
  for (const key of keys) if (Array.isArray(object[key])) return object[key];
  return null;
}

function findHeader(headers, aliases) {
  const normalized = headers.map((value) => cleanHeader(value).toLocaleLowerCase("en-US"));
  for (let index = 0; index < normalized.length; index += 1) {
    if (aliases.some((alias) => normalized[index] === cleanHeader(alias).toLocaleLowerCase("en-US") || normalized[index].includes(cleanHeader(alias).toLocaleLowerCase("en-US")))) return index;
  }
  return -1;
}

function cleanHeader(value) {
  return String(value ?? "").replace(/<[^>]*>/g, "").replace(/\u00a0/g, "").replace(/\s+/g, "").trim();
}

function cell(row, index) {
  if (index < 0 || index >= row.length) return "";
  return row[index] == null ? "" : String(row[index]).trim();
}

function parseNumber(value) {
  const text = String(value ?? "").replaceAll(",", "").replaceAll("−", "-").trim();
  if (!text || ["-", "--", "X"].includes(text)) return null;
  const number = Number(text);
  return Number.isFinite(number) ? number : null;
}

function isCommonStock(symbol, name) {
  if (!/^\d{4,6}$/.test(symbol) || symbol.startsWith("00")) return false;
  const upperName = String(name).toLocaleUpperCase("en-US");
  return !upperName.includes("ETF") && !upperName.includes("ETN");
}

function safeMessage(error) {
  return error instanceof Error ? error.message.slice(0, 160) : "行情來源暫時無法連線。";
}

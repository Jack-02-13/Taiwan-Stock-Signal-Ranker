const SIGNAL_LABELS = [
  "收盤高於20日線",
  "20日線高於60日線",
  "60日線上彎",
  "20日線上彎",
  "MACD多方",
  "MACD動能增強",
  "RSI 50至70",
  "KD多方且未過熱",
  "突破20日高點",
  "突破60日高點",
  "成交量放大",
  "OBV偏多",
  "MFI偏多",
  "ADX趨勢偏多",
  "20日報酬為正",
];

const MIN_SIGNAL_COUNT = 8;
const MIN_PRICE = 10;
const MIN_VOLUME_LOTS = 2000;

export function calculateLatestRanking(history, currentBars, targetDate) {
  if (!Array.isArray(history?.dates) || !Array.isArray(history?.stocks)) {
    throw new Error("歷史行情格式錯誤。");
  }
  const targetHistoryIndex = history.dates.indexOf(targetDate);
  const historyEndIndex = targetHistoryIndex >= 0 ? targetHistoryIndex + 1 : history.dates.length;
  const firstIncludedIndex = Math.max(0, historyEndIndex - (targetHistoryIndex >= 0 ? 77 : 76));
  const bySymbol = new Map();

  for (const stock of history.stocks) {
    if (!stock || typeof stock.symbol !== "string" || !Array.isArray(stock.bars)) continue;
    const series = [];
    for (const entry of stock.bars) {
      if (!Array.isArray(entry) || entry.length < 6) continue;
      const [dateIndex, open, high, low, close, volume] = entry;
      if (!Number.isInteger(dateIndex) || dateIndex < firstIncludedIndex || dateIndex >= historyEndIndex) continue;
      if (dateIndex === targetHistoryIndex) continue;
      const day = history.dates[dateIndex];
      if (!day || ![open, high, low, close, volume].every(Number.isFinite)) continue;
      series.push({ symbol: stock.symbol, name: stock.name || stock.symbol, date: day, open, high, low, close, volume });
    }
    if (series.length) bySymbol.set(stock.symbol, series);
  }

  const targetBars = targetHistoryIndex >= 0
    ? history.stocks.flatMap((stock) => {
      if (!Array.isArray(stock?.bars)) return [];
      const entry = stock.bars.find((bar) => Array.isArray(bar) && bar[0] === targetHistoryIndex);
      if (!entry || entry.length < 6) return [];
      const [dateIndex, open, high, low, close, volume] = entry;
      if (![open, high, low, close, volume].every(Number.isFinite)) return [];
      return [{ symbol: stock.symbol, name: stock.name || stock.symbol, date: targetDate, open, high, low, close, volume }];
    })
    : (Array.isArray(currentBars) ? currentBars : []);

  for (const bar of targetBars) {
    if (!bar || typeof bar.symbol !== "string" || ![bar.open, bar.high, bar.low, bar.close, bar.volume].every(Number.isFinite)) continue;
    const series = bySymbol.get(bar.symbol) || [];
    series.push({ ...bar, date: targetDate });
    series.sort((left, right) => left.date.localeCompare(right.date));
    bySymbol.set(bar.symbol, series);
  }

  const candidates = [];
  for (const series of bySymbol.values()) {
    const index = series.length - 1;
    if (index < 70 || series[index].date !== targetDate) continue;
    const pick = evaluate(series, index);
    if (pick) candidates.push(pick);
  }
  candidates.sort((left, right) =>
    (right.score - left.score)
    || (right.volumeShares - left.volumeShares)
    || left.symbol.localeCompare(right.symbol, "en"));

  return {
    date: targetDate,
    qualifiedCount: candidates.length,
    picks: candidates.slice(0, 3).map((candidate, index) => ({ rank: index + 1, ...candidate })),
  };
}

function average(values, start, end) {
  let total = 0;
  for (let index = start; index <= end; index += 1) total += values[index];
  return total / (end - start + 1);
}

function maximum(values, start, end) {
  let result = Number.NEGATIVE_INFINITY;
  for (let index = start; index <= end; index += 1) result = Math.max(result, values[index]);
  return result;
}

function stochastic(bars, index) {
  let k = 50;
  let d = 50;
  const firstDay = Math.max(8, index - 15);
  for (let dayIndex = firstDay; dayIndex <= index; dayIndex += 1) {
    let high = Number.NEGATIVE_INFINITY;
    let low = Number.POSITIVE_INFINITY;
    for (let cursor = dayIndex - 8; cursor <= dayIndex; cursor += 1) {
      high = Math.max(high, bars[cursor].high);
      low = Math.min(low, bars[cursor].low);
    }
    const rsv = high === low ? 50 : ((bars[dayIndex].close - low) / (high - low)) * 100;
    if (dayIndex === firstDay) {
      k = rsv;
      d = rsv;
    } else {
      k = (2 / 3) * k + (1 / 3) * rsv;
      d = (2 / 3) * d + (1 / 3) * k;
    }
  }
  return [k, d];
}

function moneyFlowIndex(bars, index) {
  let positive = 0;
  let negative = 0;
  for (let dayIndex = index - 13; dayIndex <= index; dayIndex += 1) {
    const current = bars[dayIndex];
    const previous = bars[dayIndex - 1];
    const typical = (current.high + current.low + current.close) / 3;
    const previousTypical = (previous.high + previous.low + previous.close) / 3;
    const flow = typical * current.volume;
    if (typical > previousTypical) positive += flow;
    else if (typical < previousTypical) negative += flow;
  }
  if (negative === 0) return positive > 0 ? 100 : 50;
  const ratio = positive / negative;
  return 100 - 100 / (1 + ratio);
}

function directionalStrength(bars, index) {
  const dx = new Array(index + 1).fill(0);
  let smoothedTrueRange = 0;
  let smoothedPlus = 0;
  let smoothedMinus = 0;
  let plusDi = 0;
  let minusDi = 0;
  for (let dayIndex = 1; dayIndex <= index; dayIndex += 1) {
    const current = bars[dayIndex];
    const previous = bars[dayIndex - 1];
    const up = current.high - previous.high;
    const down = previous.low - current.low;
    const plus = up > down && up > 0 ? up : 0;
    const minus = down > up && down > 0 ? down : 0;
    const trueRange = Math.max(
      current.high - current.low,
      Math.abs(current.high - previous.close),
      Math.abs(current.low - previous.close),
    );
    if (dayIndex <= 14) {
      smoothedTrueRange += trueRange;
      smoothedPlus += plus;
      smoothedMinus += minus;
    } else {
      smoothedTrueRange = smoothedTrueRange - smoothedTrueRange / 14 + trueRange;
      smoothedPlus = smoothedPlus - smoothedPlus / 14 + plus;
      smoothedMinus = smoothedMinus - smoothedMinus / 14 + minus;
    }
    if (dayIndex < 14 || smoothedTrueRange === 0) continue;
    plusDi = smoothedPlus / smoothedTrueRange * 100;
    minusDi = smoothedMinus / smoothedTrueRange * 100;
    const total = plusDi + minusDi;
    dx[dayIndex] = total === 0 ? 0 : Math.abs(plusDi - minusDi) / total * 100;
  }

  let adx = 0;
  const firstAdxDay = 27;
  if (index >= firstAdxDay) {
    for (let dayIndex = 14; dayIndex <= firstAdxDay; dayIndex += 1) adx += dx[dayIndex];
    adx /= 14;
    for (let dayIndex = firstAdxDay + 1; dayIndex <= index; dayIndex += 1) {
      adx = (adx * 13 + dx[dayIndex]) / 14;
    }
  }
  return [adx, plusDi - minusDi];
}

function evaluate(bars, index) {
  const current = bars[index];
  const closes = bars.map((bar) => bar.close);
  const volumes = bars.map((bar) => bar.volume);
  const highs = bars.map((bar) => bar.high);
  const ma20 = average(closes, index - 19, index);
  const ma60 = average(closes, index - 59, index);
  const ma20FiveDaysAgo = average(closes, index - 24, index - 5);
  const ma60TenDaysAgo = average(closes, index - 69, index - 10);
  const previousHigh20 = maximum(highs, index - 20, index - 1);
  const previousHigh60 = maximum(highs, index - 60, index - 1);
  const averageVolume20 = average(volumes, index - 20, index - 1);

  let ema12 = bars[0].close;
  let ema26 = bars[0].close;
  let signal = 0;
  let macd = 0;
  let previousHistogram = 0;
  let histogram = 0;
  for (let dayIndex = 0; dayIndex <= index; dayIndex += 1) {
    const close = bars[dayIndex].close;
    if (dayIndex > 0) {
      ema12 = close * (2 / 13) + ema12 * (11 / 13);
      ema26 = close * (2 / 27) + ema26 * (25 / 27);
    }
    macd = ema12 - ema26;
    signal = dayIndex === 0 ? macd : macd * 0.2 + signal * 0.8;
    const nextHistogram = macd - signal;
    if (dayIndex === index - 1) previousHistogram = nextHistogram;
    if (dayIndex === index) histogram = nextHistogram;
  }

  let gains = 0;
  let losses = 0;
  for (let dayIndex = index - 13; dayIndex <= index; dayIndex += 1) {
    const change = closes[dayIndex] - closes[dayIndex - 1];
    if (change > 0) gains += change;
    else if (change < 0) losses -= change;
  }
  gains /= 14;
  losses /= 14;
  const rsi = losses === 0 ? 100 : 100 - 100 / (1 + gains / losses);
  const [kValue, dValue] = stochastic(bars, index);
  const mfi = moneyFlowIndex(bars, index);
  const [adx, diDifference] = directionalStrength(bars, index);

  let obv = 0;
  const obvValues = new Array(index + 1).fill(0);
  for (let dayIndex = 1; dayIndex <= index; dayIndex += 1) {
    const change = closes[dayIndex] - closes[dayIndex - 1];
    obv = change > 0 ? obv + volumes[dayIndex] : change < 0 ? obv - volumes[dayIndex] : obv;
    obvValues[dayIndex] = obv;
  }
  const obvAverage = average(obvValues, index - 19, index);

  const checks = [
    current.close > ma20,
    ma20 > ma60,
    ma60 > ma60TenDaysAgo,
    ma20 > ma20FiveDaysAgo,
    macd > signal,
    histogram > previousHistogram,
    rsi >= 50 && rsi <= 70,
    kValue > dValue && kValue < 80,
    current.close > previousHigh20,
    current.close > previousHigh60,
    current.volume > averageVolume20 * 1.2,
    obvValues[index] > obvAverage,
    mfi > 50,
    adx > 20 && diDifference > 0,
    current.close / closes[index - 20] - 1 > 0,
  ];
  const signalIndexes = checks.flatMap((passed, signalIndex) => passed ? [signalIndex] : []);
  const score = signalIndexes.length * 10;
  if (score < MIN_SIGNAL_COUNT * 10 || current.volume / 1000 < MIN_VOLUME_LOTS || current.close <= MIN_PRICE) return null;

  return {
    symbol: current.symbol,
    name: current.name,
    close: Math.round(current.close * 10000) / 10000,
    volumeLots: Math.round(current.volume / 10) / 100,
    volumeShares: Math.trunc(current.volume),
    score,
    signals: signalIndexes.map((signalIndex) => SIGNAL_LABELS[signalIndex]),
  };
}

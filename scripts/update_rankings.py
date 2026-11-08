#!/usr/bin/env python3
"""Fetch public TWSE/TPEx daily bars and write the public Pages ranking file."""

from __future__ import annotations

import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_FILE = ROOT / "site" / "data" / "rankings.json"
MIN_SIGNAL_COUNT = 8
MIN_PRICE = 10.0
MIN_VOLUME_LOTS = 2_000.0
MIN_SCORE = MIN_SIGNAL_COUNT * 10
HISTORY_SESSIONS = 77  # 70 warm-up sessions plus the latest day and six prior days.
MAX_LOOKBACK_DAYS = 260
USER_AGENT = "TaiwanStockSignals-GitHubPages/1.0"

SIGNAL_LABELS = (
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
)


@dataclass(frozen=True)
class Bar:
    symbol: str
    name: str
    market: str
    day: date
    open: float
    high: float
    low: float
    close: float
    volume: float


def request_json(url: str) -> Any:
    request = Request(
        url,
        headers={
            "Accept": "application/json,text/plain,*/*",
            "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.7",
            "User-Agent": USER_AGENT,
        },
    )
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8-sig"))
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(0.6 * (attempt + 1))
    raise RuntimeError(f"Public market data request failed: {url}") from last_error


def parse_number(value: Any) -> float | None:
    if value is None:
        return None
    text = str(value).replace(",", "").replace("−", "-").strip()
    if not text or text in {"-", "--", "X", "無"}:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def clean_header(value: Any) -> str:
    text = re.sub(r"<[^>]*>", "", str(value))
    return text.replace("\xa0", "").replace(" ", "").strip()


def find_column(headers: list[Any], aliases: tuple[str, ...]) -> int:
    normalized = [clean_header(value).casefold() for value in headers]
    for index, header in enumerate(normalized):
        for alias in aliases:
            if header == alias.casefold() or alias.casefold() in header:
                return index
    return -1


def table_rows(payload: Any) -> list[tuple[list[Any], list[Any]]]:
    tables = payload.get("tables") if isinstance(payload, dict) else None
    if not isinstance(tables, list) and isinstance(payload, dict):
        tables = [payload]
    parsed: list[tuple[list[Any], list[Any]]] = []
    for table in tables or []:
        if not isinstance(table, dict):
            continue
        headers = table.get("fields") or table.get("Field") or table.get("headers")
        rows = table.get("data") or table.get("aaData") or table.get("rows")
        if not isinstance(headers, list) or not isinstance(rows, list):
            continue
        parsed.append((headers, rows))
    return parsed


def is_common_stock(symbol: str, name: str) -> bool:
    if not re.fullmatch(r"[0-9]{4,6}", symbol):
        return False
    upper_name = name.upper()
    return not symbol.startswith("00") and "ETF" not in upper_name and "ETN" not in upper_name


def parse_market_rows(payload: Any, market: str, day: date) -> list[Bar]:
    bars: list[Bar] = []
    for headers, rows in table_rows(payload):
        code_index = find_column(headers, ("證券代號", "股票代號", "代號", "securitiescompanycode", "code"))
        name_index = find_column(headers, ("證券名稱", "股票名稱", "名稱", "companyname", "name"))
        open_index = find_column(headers, ("開盤價", "開盤", "open"))
        high_index = find_column(headers, ("最高價", "最高", "high"))
        low_index = find_column(headers, ("最低價", "最低", "low"))
        close_index = find_column(headers, ("收盤價", "收盤", "close"))
        volume_index = find_column(headers, ("成交股數", "成交量(股)", "成交量", "tradingshares", "volume"))
        if code_index < 0 or close_index < 0 or volume_index < 0:
            continue

        for row in rows:
            values = row.get("value") if isinstance(row, dict) else row
            if not isinstance(values, list) or len(values) <= max(code_index, close_index, volume_index):
                continue
            symbol = str(values[code_index] or "").strip()
            name = str(values[name_index] or "").strip() if 0 <= name_index < len(values) else symbol
            if not is_common_stock(symbol, name):
                continue

            close = parse_number(values[close_index])
            volume = parse_number(values[volume_index])
            open_price = parse_number(values[open_index]) if 0 <= open_index < len(values) else None
            high = parse_number(values[high_index]) if 0 <= high_index < len(values) else None
            low = parse_number(values[low_index]) if 0 <= low_index < len(values) else None
            if close is None or volume is None or close <= 0 or volume <= 0:
                continue
            open_price = close if open_price is None else open_price
            high = max(open_price, close) if high is None else high
            low = min(open_price, close) if low is None else low
            bars.append(Bar(symbol, name, market, day, open_price, high, low, close, volume))
    return bars


def fetch_twse(day: date) -> list[Bar]:
    query = urlencode({"date": day.strftime("%Y%m%d"), "type": "ALLBUT0999", "response": "json"})
    payload = request_json(f"https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?{query}")
    return parse_market_rows(payload, "TWSE", day)


def fetch_tpex(day: date) -> list[Bar]:
    # dailyQuotes is the TPEx historical all-market quote table. The older
    # afterTrading/otc action currently returns an empty table for these dates.
    query = urlencode({"response": "json", "date": day.strftime("%Y/%m/%d")})
    payload = request_json(f"https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?{query}")
    return parse_market_rows(payload, "TPEx", day)


def collect_history() -> tuple[list[Bar], list[date]]:
    today = datetime.now(ZoneInfo("Asia/Taipei")).date()
    bars: list[Bar] = []
    twse_dates: set[date] = set()
    tpex_dates: set[date] = set()

    with ThreadPoolExecutor(max_workers=2) as pool:
        for offset in range(MAX_LOOKBACK_DAYS):
            day = today - timedelta(days=offset)
            if day.weekday() >= 5:
                continue
            twse_future = pool.submit(fetch_twse, day)
            tpex_future = pool.submit(fetch_tpex, day)
            twse_bars = twse_future.result()
            tpex_bars = tpex_future.result()
            if twse_bars:
                twse_dates.add(day)
                bars.extend(twse_bars)
            if tpex_bars:
                tpex_dates.add(day)
                bars.extend(tpex_bars)
            common_dates = sorted(twse_dates & tpex_dates)
            if len(common_dates) >= HISTORY_SESSIONS:
                selected_dates = common_dates[-HISTORY_SESSIONS:]
                selected_set = set(selected_dates)
                return [bar for bar in bars if bar.day in selected_set], selected_dates

    raise RuntimeError(
        "Could not collect 77 dates with both TWSE and TPEx data. "
        "No ranking file was changed; rerun the workflow later."
    )


def average(values: list[float], start: int, end: int) -> float:
    return sum(values[start : end + 1]) / (end - start + 1)


def max_value(values: list[float], start: int, end: int) -> float:
    return max(values[start : end + 1])


def min_value(values: list[float], start: int, end: int) -> float:
    return min(values[start : end + 1])


def stochastic(bars: list[Bar], index: int) -> tuple[float, float]:
    k = 50.0
    d = 50.0
    first_day = max(8, index - 15)
    for day_index in range(first_day, index + 1):
        high = max(bar.high for bar in bars[day_index - 8 : day_index + 1])
        low = min(bar.low for bar in bars[day_index - 8 : day_index + 1])
        rsv = 50.0 if high == low else (bars[day_index].close - low) / (high - low) * 100.0
        if day_index == first_day:
            k = rsv
            d = rsv
        else:
            k = (2.0 / 3.0) * k + (1.0 / 3.0) * rsv
            d = (2.0 / 3.0) * d + (1.0 / 3.0) * k
    return k, d


def money_flow_index(bars: list[Bar], index: int) -> float:
    positive = 0.0
    negative = 0.0
    for day_index in range(index - 13, index + 1):
        current = bars[day_index]
        previous = bars[day_index - 1]
        typical = (current.high + current.low + current.close) / 3.0
        previous_typical = (previous.high + previous.low + previous.close) / 3.0
        flow = typical * current.volume
        if typical > previous_typical:
            positive += flow
        elif typical < previous_typical:
            negative += flow
    if negative == 0:
        return 100.0 if positive > 0 else 50.0
    ratio = positive / negative
    return 100.0 - 100.0 / (1.0 + ratio)


def directional_strength(bars: list[Bar], index: int) -> tuple[float, float]:
    dx = [0.0] * (index + 1)
    smoothed_tr = 0.0
    smoothed_plus = 0.0
    smoothed_minus = 0.0
    plus_di = 0.0
    minus_di = 0.0
    for day_index in range(1, index + 1):
        current = bars[day_index]
        previous = bars[day_index - 1]
        up = current.high - previous.high
        down = previous.low - current.low
        plus = up if up > down and up > 0 else 0.0
        minus = down if down > up and down > 0 else 0.0
        true_range = max(
            current.high - current.low,
            abs(current.high - previous.close),
            abs(current.low - previous.close),
        )
        if day_index <= 14:
            smoothed_tr += true_range
            smoothed_plus += plus
            smoothed_minus += minus
        else:
            smoothed_tr = smoothed_tr - smoothed_tr / 14.0 + true_range
            smoothed_plus = smoothed_plus - smoothed_plus / 14.0 + plus
            smoothed_minus = smoothed_minus - smoothed_minus / 14.0 + minus
        if day_index < 14 or smoothed_tr == 0:
            continue
        plus_di = smoothed_plus / smoothed_tr * 100.0
        minus_di = smoothed_minus / smoothed_tr * 100.0
        total = plus_di + minus_di
        dx[day_index] = 0.0 if total == 0 else abs(plus_di - minus_di) / total * 100.0

    adx = 0.0
    first_adx_day = 27
    if index >= first_adx_day:
        adx = sum(dx[14 : first_adx_day + 1]) / 14.0
        for day_index in range(first_adx_day + 1, index + 1):
            adx = (adx * 13.0 + dx[day_index]) / 14.0
    return adx, plus_di - minus_di


def obv_values(bars: list[Bar], index: int) -> list[float]:
    values = [0.0] * (index + 1)
    for day_index in range(1, index + 1):
        change = bars[day_index].close - bars[day_index - 1].close
        if change > 0:
            values[day_index] = values[day_index - 1] + bars[day_index].volume
        elif change < 0:
            values[day_index] = values[day_index - 1] - bars[day_index].volume
        else:
            values[day_index] = values[day_index - 1]
    return values


def evaluate(bars: list[Bar], index: int) -> dict[str, Any] | None:
    current = bars[index]
    closes = [bar.close for bar in bars]
    volumes = [bar.volume for bar in bars]
    highs = [bar.high for bar in bars]

    ma20 = average(closes, index - 19, index)
    ma60 = average(closes, index - 59, index)
    ma20_five_days_ago = average(closes, index - 24, index - 5)
    ma60_ten_days_ago = average(closes, index - 69, index - 10)
    previous_high20 = max_value(highs, index - 20, index - 1)
    previous_high60 = max_value(highs, index - 60, index - 1)
    average_volume20 = average(volumes, index - 20, index - 1)

    macd_values: list[float] = []
    signal_values: list[float] = []
    ema12 = bars[0].close
    ema26 = bars[0].close
    signal = 0.0
    for day_index, bar in enumerate(bars[: index + 1]):
        close = bar.close
        if day_index > 0:
            ema12 = close * (2.0 / 13.0) + ema12 * (11.0 / 13.0)
            ema26 = close * (2.0 / 27.0) + ema26 * (25.0 / 27.0)
        macd = ema12 - ema26
        signal = macd if day_index == 0 else macd * 0.2 + signal * 0.8
        macd_values.append(macd)
        signal_values.append(signal)
    histogram = macd_values[index] - signal_values[index]
    prior_histogram = macd_values[index - 1] - signal_values[index - 1]

    gains = 0.0
    losses = 0.0
    for day_index in range(index - 13, index + 1):
        change = closes[day_index] - closes[day_index - 1]
        if change > 0:
            gains += change
        elif change < 0:
            losses -= change
    gains /= 14.0
    losses /= 14.0
    rsi = 100.0 if losses == 0 else 100.0 - 100.0 / (1.0 + gains / losses)

    k_value, d_value = stochastic(bars, index)
    mfi = money_flow_index(bars, index)
    adx, di_difference = directional_strength(bars, index)
    obv = obv_values(bars, index)
    obv_average = average(obv, index - 19, index)

    checks = (
        current.close > ma20,
        ma20 > ma60,
        ma60 > ma60_ten_days_ago,
        ma20 > ma20_five_days_ago,
        macd_values[index] > signal_values[index],
        histogram > prior_histogram,
        50 <= rsi <= 70,
        k_value > d_value and k_value < 80,
        current.close > previous_high20,
        current.close > previous_high60,
        current.volume > average_volume20 * 1.2,
        obv[index] > obv_average,
        mfi > 50,
        adx > 20 and di_difference > 0,
        current.close / closes[index - 20] - 1.0 > 0,
    )
    signal_indexes = [signal_index for signal_index, passed in enumerate(checks) if passed]
    score = len(signal_indexes) * 10
    if score < MIN_SCORE or current.volume / 1000.0 < MIN_VOLUME_LOTS or current.close <= MIN_PRICE:
        return None

    return {
        "symbol": current.symbol,
        "name": current.name,
        "close": round(current.close, 4),
        "volumeLots": round(current.volume / 1000.0, 2),
        "volumeShares": int(current.volume),
        "score": score,
        "signals": [SIGNAL_LABELS[signal_index] for signal_index in signal_indexes],
    }


def build_rankings(bars: list[Bar], dates: list[date]) -> list[dict[str, Any]]:
    by_symbol: dict[str, list[Bar]] = {}
    for bar in bars:
        by_symbol.setdefault(bar.symbol, []).append(bar)
    for series in by_symbol.values():
        series.sort(key=lambda bar: bar.day)

    rankings: list[dict[str, Any]] = []
    for target_day in reversed(dates[-7:]):
        candidates: list[dict[str, Any]] = []
        for series in by_symbol.values():
            low = 0
            high = len(series) - 1
            index = -1
            while low <= high:
                middle = (low + high) // 2
                if series[middle].day == target_day:
                    index = middle
                    break
                if series[middle].day < target_day:
                    low = middle + 1
                else:
                    high = middle - 1
            if index < 70:
                continue
            pick = evaluate(series, index)
            if pick is not None:
                candidates.append(pick)

        candidates.sort(key=lambda item: (-item["score"], -item["volumeShares"], item["symbol"]))
        picks = []
        for rank, candidate in enumerate(candidates[:3], start=1):
            picks.append({"rank": rank, **candidate})
        rankings.append({
            "date": target_day.isoformat(),
            "qualifiedCount": len(candidates),
            "picks": picks,
        })
    return rankings


def write_rankings(rankings: list[dict[str, Any]], dates: list[date]) -> None:
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    previous: dict[str, Any] = {}
    if OUTPUT_FILE.exists():
        try:
            previous = json.loads(OUTPUT_FILE.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            previous = {}

    latest_day = dates[-1].isoformat()
    generated_at = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    if previous.get("latestDate") == latest_day and previous.get("rankings") == rankings:
        generated_at = previous.get("generatedAt") or generated_at

    document = {
        "schemaVersion": 1,
        "generatedAt": generated_at,
        "latestDate": latest_day,
        "historySessionCount": len(dates),
        "sources": [
            "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX",
            "https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes",
        ],
        "method": {
            "signals": 15,
            "pointsPerSignal": 10,
            "minimumSignalCount": MIN_SIGNAL_COUNT,
            "minimumPrice": MIN_PRICE,
            "minimumVolumeLots": MIN_VOLUME_LOTS,
            "sort": ["score_desc", "volume_desc"],
        },
        "rankings": rankings,
    }
    temporary_file = OUTPUT_FILE.with_suffix(".tmp")
    temporary_file.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary_file.replace(OUTPUT_FILE)


def main() -> int:
    bars, dates = collect_history()
    rankings = build_rankings(bars, dates)
    if not rankings:
        raise RuntimeError("No daily ranking dates were produced.")
    write_rankings(rankings, dates)
    top_picks = rankings[0]["picks"]
    print(f"Updated {len(dates)} shared trading sessions through {dates[-1].isoformat()}.")
    print(f"Qualified stocks on latest session: {rankings[0]['qualifiedCount']}.")
    for pick in top_picks:
        print(f"{pick['rank']}. {pick['symbol']} {pick['name']} — {pick['score']} points")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Ranking update failed: {error}", file=sys.stderr)
        raise


package com.example.taiwanstocktop3;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class StockAnalyzer {
    private static final int MIN_SIGNAL_COUNT = 8;
    private static final double MIN_VOLUME_LOTS = 2000.0;
    private static final double MIN_PRICE = 10.0;
    private static final String[] LABELS = {
            "收盤高於20日線", "20日線高於60日線", "60日線上彎", "20日線上彎",
            "MACD多方", "MACD動能增強", "RSI 50至70", "KD多方且未過熱",
            "突破20日高點", "突破60日高點", "成交量放大", "OBV偏多",
            "MFI偏多", "ADX趨勢偏多", "20日報酬為正"
    };

    private StockAnalyzer() {}

    static List<MarketData.DayRanking> rank(List<MarketData.Bar> allBars) {
        Map<String, List<MarketData.Bar>> bySymbol = new HashMap<>();
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (MarketData.Bar bar : allBars) {
            bySymbol.computeIfAbsent(bar.symbol, ignored -> new ArrayList<>()).add(bar);
            dates.add(bar.date);
        }
        if (dates.isEmpty()) return new ArrayList<>();

        ArrayList<LocalDate> recentDates = new ArrayList<>(dates);
        int first = Math.max(0, recentDates.size() - 7);
        recentDates = new ArrayList<>(recentDates.subList(first, recentDates.size()));
        Collections.reverse(recentDates);
        for (List<MarketData.Bar> series : bySymbol.values()) {
            series.sort(Comparator.comparing((MarketData.Bar bar) -> bar.date));
        }

        ArrayList<MarketData.DayRanking> rankings = new ArrayList<>();
        for (LocalDate date : recentDates) {
            ArrayList<Candidate> candidates = new ArrayList<>();
            for (Map.Entry<String, List<MarketData.Bar>> entry : bySymbol.entrySet()) {
                List<MarketData.Bar> series = entry.getValue();
                int index = findDate(series, date);
                if (index < 70) continue;
                Candidate candidate = evaluate(series, index);
                if (candidate != null) candidates.add(candidate);
            }
            candidates.sort((a, b) -> {
                int scoreOrder = Integer.compare(b.score, a.score);
                return scoreOrder != 0 ? scoreOrder : Double.compare(b.volume, a.volume);
            });
            ArrayList<MarketData.Pick> picks = new ArrayList<>();
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                Candidate c = candidates.get(i);
                picks.add(new MarketData.Pick(i + 1, c.symbol, c.name, c.close,
                        c.volume / 1000.0, c.score, c.signals));
            }
            rankings.add(new MarketData.DayRanking(date, picks, candidates.size()));
        }
        return rankings;
    }

    private static int findDate(List<MarketData.Bar> series, LocalDate date) {
        int low = 0, high = series.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int comparison = series.get(mid).date.compareTo(date);
            if (comparison == 0) return mid;
            if (comparison < 0) low = mid + 1; else high = mid - 1;
        }
        return -1;
    }

    private static Candidate evaluate(List<MarketData.Bar> bars, int i) {
        MarketData.Bar today = bars.get(i);
        double ma20 = averageClose(bars, i - 19, i);
        double ma60 = averageClose(bars, i - 59, i);
        double ma20FiveDaysAgo = averageClose(bars, i - 24, i - 5);
        double ma60TenDaysAgo = averageClose(bars, i - 69, i - 10);
        double previousHigh20 = maxHigh(bars, i - 20, i - 1);
        double previousHigh60 = maxHigh(bars, i - 60, i - 1);
        double averageVolume20 = averageVolume(bars, i - 20, i - 1);

        double[] macd = new double[i + 1];
        double[] macdSignal = new double[i + 1];
        double ema12 = bars.get(0).close;
        double ema26 = bars.get(0).close;
        double signal = 0;
        for (int d = 0; d <= i; d++) {
            double close = bars.get(d).close;
            if (d > 0) {
                ema12 = close * (2.0 / 13.0) + ema12 * (11.0 / 13.0);
                ema26 = close * (2.0 / 27.0) + ema26 * (25.0 / 27.0);
            }
            macd[d] = ema12 - ema26;
            signal = d == 0 ? macd[d] : macd[d] * 0.2 + signal * 0.8;
            macdSignal[d] = signal;
        }
        double histogram = macd[i] - macdSignal[i];
        double priorHistogram = macd[i - 1] - macdSignal[i - 1];

        double gains = 0, losses = 0;
        for (int d = i - 13; d <= i; d++) {
            double change = bars.get(d).close - bars.get(d - 1).close;
            if (change > 0) gains += change;
            if (change < 0) losses -= change;
        }
        gains /= 14.0;
        losses /= 14.0;
        double rsi = losses == 0 ? 100.0 : 100.0 - 100.0 / (1.0 + gains / losses);

        double[] stochastic = stochastic(bars, i);
        double moneyFlowIndex = moneyFlowIndex(bars, i);
        double[] directional = directionalStrength(bars, i);
        double adx = directional[0];
        double[] obv = obv(bars, i);
        double obvAverage = average(obv, i - 19, i);

        boolean[] checks = {
                today.close > ma20,
                ma20 > ma60,
                ma60 > ma60TenDaysAgo,
                ma20 > ma20FiveDaysAgo,
                macd[i] > macdSignal[i],
                histogram > priorHistogram,
                rsi >= 50 && rsi <= 70,
                stochastic[0] > stochastic[1] && stochastic[0] < 80,
                today.close > previousHigh20,
                today.close > previousHigh60,
                today.volume > averageVolume20 * 1.2,
                obv[i] > obvAverage,
                moneyFlowIndex > 50,
                adx > 20 && directional[1] > 0,
                today.close / bars.get(i - 20).close - 1.0 > 0
        };
        int score = 0;
        ArrayList<String> signals = new ArrayList<>();
        for (int signalIndex = 0; signalIndex < checks.length; signalIndex++) {
            if (checks[signalIndex]) {
                score += 10;
                signals.add(LABELS[signalIndex]);
            }
        }
        if (score < MIN_SIGNAL_COUNT * 10 || today.volume / 1000.0 < MIN_VOLUME_LOTS || today.close <= MIN_PRICE) return null;
        return new Candidate(today.symbol, today.name, today.close, today.volume, score, signals);
    }

    private static double[] stochastic(List<MarketData.Bar> bars, int i) {
        double k = 50, d = 50;
        for (int day = Math.max(8, i - 15); day <= i; day++) {
            double high = maxHigh(bars, day - 8, day);
            double low = minLow(bars, day - 8, day);
            double rsv = high == low ? 50 : (bars.get(day).close - low) / (high - low) * 100.0;
            if (day == Math.max(8, i - 15)) {
                k = rsv;
                d = rsv;
            } else {
                k = (2.0 / 3.0) * k + (1.0 / 3.0) * rsv;
                d = (2.0 / 3.0) * d + (1.0 / 3.0) * k;
            }
        }
        return new double[]{k, d};
    }

    private static double moneyFlowIndex(List<MarketData.Bar> bars, int i) {
        double positive = 0, negative = 0;
        for (int day = i - 13; day <= i; day++) {
            MarketData.Bar now = bars.get(day), before = bars.get(day - 1);
            double typical = (now.high + now.low + now.close) / 3.0;
            double previousTypical = (before.high + before.low + before.close) / 3.0;
            double flow = typical * now.volume;
            if (typical > previousTypical) positive += flow;
            else if (typical < previousTypical) negative += flow;
        }
        if (negative == 0) return positive > 0 ? 100 : 50;
        double ratio = positive / negative;
        return 100.0 - 100.0 / (1.0 + ratio);
    }

    private static double[] directionalStrength(List<MarketData.Bar> bars, int i) {
        double[] dx = new double[i + 1];
        double smoothedTr = 0, smoothedPlus = 0, smoothedMinus = 0;
        double plusDi = 0, minusDi = 0;
        for (int day = 1; day <= i; day++) {
            MarketData.Bar now = bars.get(day), before = bars.get(day - 1);
            double up = now.high - before.high;
            double down = before.low - now.low;
            double plus = up > down && up > 0 ? up : 0;
            double minus = down > up && down > 0 ? down : 0;
            double tr = Math.max(now.high - now.low,
                    Math.max(Math.abs(now.high - before.close), Math.abs(now.low - before.close)));
            if (day <= 14) {
                smoothedTr += tr;
                smoothedPlus += plus;
                smoothedMinus += minus;
            } else {
                smoothedTr = smoothedTr - smoothedTr / 14.0 + tr;
                smoothedPlus = smoothedPlus - smoothedPlus / 14.0 + plus;
                smoothedMinus = smoothedMinus - smoothedMinus / 14.0 + minus;
            }
            if (day < 14 || smoothedTr == 0) continue;
            plusDi = smoothedPlus / smoothedTr * 100.0;
            minusDi = smoothedMinus / smoothedTr * 100.0;
            double sum = plusDi + minusDi;
            dx[day] = sum == 0 ? 0 : Math.abs(plusDi - minusDi) / sum * 100.0;
        }
        double adx = 0;
        int firstAdxDay = 27;
        if (i >= firstAdxDay) {
            for (int day = 14; day <= firstAdxDay; day++) adx += dx[day];
            adx /= 14.0;
            for (int day = firstAdxDay + 1; day <= i; day++) {
                adx = (adx * 13.0 + dx[day]) / 14.0;
            }
        }
        return new double[]{adx, plusDi - minusDi};
    }

    private static double[] obv(List<MarketData.Bar> bars, int i) {
        double[] values = new double[i + 1];
        for (int day = 1; day <= i; day++) {
            double change = bars.get(day).close - bars.get(day - 1).close;
            values[day] = values[day - 1] + (change > 0 ? bars.get(day).volume
                    : (change < 0 ? -bars.get(day).volume : 0));
        }
        return values;
    }

    private static double averageClose(List<MarketData.Bar> bars, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) sum += bars.get(i).close;
        return sum / (to - from + 1);
    }

    private static double averageVolume(List<MarketData.Bar> bars, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) sum += bars.get(i).volume;
        return sum / (to - from + 1);
    }

    private static double average(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) sum += values[i];
        return sum / (to - from + 1);
    }

    private static double maxHigh(List<MarketData.Bar> bars, int from, int to) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = from; i <= to; i++) max = Math.max(max, bars.get(i).high);
        return max;
    }

    private static double minLow(List<MarketData.Bar> bars, int from, int to) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = from; i <= to; i++) min = Math.min(min, bars.get(i).low);
        return min;
    }

    private static final class Candidate {
        final String symbol;
        final String name;
        final double close;
        final double volume;
        final int score;
        final List<String> signals;

        Candidate(String symbol, String name, double close, double volume, int score, List<String> signals) {
            this.symbol = symbol;
            this.name = name;
            this.close = close;
            this.volume = volume;
            this.score = score;
            this.signals = signals;
        }
    }
}

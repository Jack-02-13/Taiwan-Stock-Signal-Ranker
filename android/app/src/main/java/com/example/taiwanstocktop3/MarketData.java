package com.example.taiwanstocktop3;

import java.time.LocalDate;
import java.util.List;

final class MarketData {
    private MarketData() {}

    static final class Bar {
        final String symbol;
        final String name;
        final String market;
        final LocalDate date;
        final double open;
        final double high;
        final double low;
        final double close;
        final double volume;

        Bar(String symbol, String name, String market, LocalDate date,
            double open, double high, double low, double close, double volume) {
            this.symbol = symbol;
            this.name = name;
            this.market = market;
            this.date = date;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    static final class Pick {
        final int rank;
        final String symbol;
        final String name;
        final double close;
        final double volumeLots;
        final int score;
        final List<String> signals;

        Pick(int rank, String symbol, String name, double close, double volumeLots,
             int score, List<String> signals) {
            this.rank = rank;
            this.symbol = symbol;
            this.name = name;
            this.close = close;
            this.volumeLots = volumeLots;
            this.score = score;
            this.signals = signals;
        }
    }

    static final class DayRanking {
        final LocalDate date;
        final List<Pick> picks;
        final int qualifiedCount;

        DayRanking(LocalDate date, List<Pick> picks, int qualifiedCount) {
            this.date = date;
            this.picks = picks;
            this.qualifiedCount = qualifiedCount;
        }
    }
}

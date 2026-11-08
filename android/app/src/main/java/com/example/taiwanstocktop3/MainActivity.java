package com.example.taiwanstocktop3;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService networkPool = Executors.newFixedThreadPool(4);
    private StockDatabase database;
    private LinearLayout content;
    private TextView status;
    private Button refreshButton;
    private boolean syncing = false;

    private static final int NAVY = Color.rgb(20, 43, 59);
    private static final int GREEN = Color.rgb(45, 126, 98);
    private static final int PAGE = Color.rgb(244, 246, 245);
    private static final int MUTED = Color.rgb(104, 119, 127);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("M/d (E)", Locale.TAIWAN);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        database = new StockDatabase(this);
        buildScreen();
        syncExecutor.execute(() -> {
            List<MarketData.DayRanking> cached = StockAnalyzer.rank(database.readAll());
            runOnUiThread(() -> displayRankings(cached));
        });
        syncMarkets();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);
        root.setPadding(0, dp(36), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(16));
        header.setBackgroundColor(NAVY);

        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("台股前三名", 23, Color.WHITE, true);
        TextView subtitle = label("15 項技術訊號 · 每項 10 分", 13, Color.rgb(203, 218, 224), false);
        titleStack.addView(title);
        titleStack.addView(subtitle);
        headingRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        refreshButton = new Button(this);
        refreshButton.setText("更新資料");
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setAllCaps(false);
        refreshButton.setBackground(tint(GREEN, 18));
        refreshButton.setOnClickListener(view -> syncMarkets());
        headingRow.addView(refreshButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        header.addView(headingRow);

        status = label("正在讀取手機內的行情紀錄…", 12, Color.rgb(214, 228, 231), false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(10);
        header.addView(status, statusLp);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(22));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void syncMarkets() {
        if (syncing) return;
        syncing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText("更新中…");
        setStatus("從證交所與櫃買中心下載行情，分析在手機上執行…");
        syncExecutor.execute(() -> {
            int done = 0;
            int failures = 0;
            LocalDate today = LocalDate.now();
            LocalDate start = today.minusDays(115);
            LocalDate refreshRecentAfter = today.minusDays(5);
            ArrayList<Callable<FetchOutcome>> tasks = new ArrayList<>();
            for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
                DayOfWeek day = date.getDayOfWeek();
                if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) continue;
                final LocalDate fetchDate = date;
                boolean forceRecent = !date.isBefore(refreshRecentAfter);
                if (forceRecent || !database.wasSynced("TWSE", date)) {
                    tasks.add(() -> download("TWSE", fetchDate));
                }
                if (forceRecent || !database.wasSynced("TPEx", date)) {
                    tasks.add(() -> download("TPEx", fetchDate));
                }
            }
            CompletionService<FetchOutcome> completed = new ExecutorCompletionService<>(networkPool);
            for (Callable<FetchOutcome> task : tasks) completed.submit(task);
            int total = tasks.size();
            for (int i = 0; i < total; i++) {
                try {
                    Future<FetchOutcome> future = completed.take();
                    FetchOutcome outcome = future.get();
                    if (outcome.error == null) {
                        database.saveBars(outcome.bars);
                        database.markSynced(outcome.market, outcome.date);
                    } else {
                        failures++;
                    }
                } catch (Exception error) {
                    failures++;
                }
                done++;
                final int completedCount = done;
                final int taskCount = total;
                runOnUiThread(() -> setStatus("下載行情中 " + completedCount + " / " + taskCount));
            }

            database.trimBefore(today.minusDays(180));
            List<MarketData.DayRanking> rankings = StockAnalyzer.rank(database.readAll());
            final int failedCount = failures;
            runOnUiThread(() -> {
                displayRankings(rankings);
                if (rankings.isEmpty()) {
                    setStatus("尚未取得足夠行情。請確認網路後按「更新資料」。");
                } else if (failedCount > 0) {
                    setStatus("部分行情來源暫時無法連線；目前顯示手機已保存的資料。");
                } else {
                    setStatus("分析完成 · 行情與計算結果保存在手機");
                }
                refreshButton.setEnabled(true);
                refreshButton.setText("更新資料");
                syncing = false;
            });
        });
    }

    private FetchOutcome download(String market, LocalDate date) {
        try {
            List<MarketData.Bar> bars = "TWSE".equals(market)
                    ? ExchangeClient.fetchTwse(date) : ExchangeClient.fetchTpex(date);
            return new FetchOutcome(market, date, bars, null);
        } catch (Exception e) {
            return new FetchOutcome(market, date, new ArrayList<>(), e);
        }
    }

    private void displayRankings(List<MarketData.DayRanking> rankings) {
        content.removeAllViews();
        if (rankings == null || rankings.isEmpty()) {
            TextView empty = label("首次使用需連網下載近期上市、上櫃行情。下載與 15 項條件計算都在手機進行，資料會保存在手機。", 15, MUTED, false);
            empty.setPadding(dp(8), dp(26), dp(8), dp(26));
            content.addView(empty);
            return;
        }

        MarketData.DayRanking latest = rankings.get(0);
        addSectionTitle("今日前三名", latest.qualifiedCount + " 檔符合 · 分數優先，同分看成交量");
        if (latest.picks.isEmpty()) {
            addEmptyMessage("當日沒有股票達到 80 / 150 分及成交門檻。");
        } else {
            for (MarketData.Pick pick : latest.picks) addTopPick(pick);
        }

        addSectionTitle("前六個交易日", "每格顯示當日前三名");
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 2; column++) {
                int rankingIndex = 1 + rowIndex * 2 + column;
                MarketData.DayRanking day = rankingIndex < rankings.size() ? rankings.get(rankingIndex) : null;
                View card = makeHistoryCard(day);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                params.setMargins(column == 0 ? 0 : dp(5), dp(5), column == 0 ? dp(5) : 0, dp(5));
                row.addView(card, params);
            }
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(grid);

        TextView note = label("日期：" + latest.date.format(DISPLAY_DATE)
                + " · 每項訊號 10 分；至少 8 項、股價高於 10 元、成交量至少 2,000 張才列入。", 12, MUTED, false);
        note.setPadding(dp(4), dp(12), dp(4), dp(8));
        content.addView(note);
    }

    private void addSectionTitle(String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView main = label(title, 18, NAVY, true);
        TextView sub = label(detail, 11, MUTED, false);
        row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(sub);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(7);
        params.bottomMargin = dp(9);
        content.addView(row, params);
    }

    private void addTopPick(MarketData.Pick pick) {
        LinearLayout card = panel();
        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rank = label(String.format(Locale.TAIWAN, "%02d", pick.rank), 18, GREEN, true);
        mainRow.addView(rank, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        String name = pick.name == null || pick.name.trim().isEmpty() ? pick.symbol : pick.name;
        details.addView(label(pick.symbol + "  " + name, 16, NAVY, true));
        details.addView(label(String.format(Locale.TAIWAN, "收盤 %.2f  ·  成交 %.0f 張", pick.close, pick.volumeLots), 12, MUTED, false));
        mainRow.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mainRow.addView(label(pick.score + "/150", 14, GREEN, true));
        card.addView(mainRow);
        if (!pick.signals.isEmpty()) {
            TextView signalText = label(joinSignals(pick.signals), 11, MUTED, false);
            LinearLayout.LayoutParams signalParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            signalParams.topMargin = dp(7);
            card.addView(signalText, signalParams);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        content.addView(card, params);
    }

    private View makeHistoryCard(MarketData.DayRanking day) {
        LinearLayout card = panel();
        card.setPadding(dp(11), dp(11), dp(11), dp(10));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label(day == null ? "—" : day.date.format(DISPLAY_DATE), 13, NAVY, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(heading);
        if (day == null || day.picks.isEmpty()) {
            TextView empty = label(day == null ? "尚無資料" : "當日無符合條件", 11, MUTED, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            card.addView(empty, lp);
            return card;
        }
        for (MarketData.Pick pick : day.picks) {
            String stockName = pick.name == null || pick.name.trim().isEmpty()
                    ? pick.symbol : pick.name;
            TextView item = label(pick.rank + ". " + stockName, 12, NAVY, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            card.addView(item, lp);
        }
        return card;
    }

    private void addEmptyMessage(String message) {
        LinearLayout card = panel();
        card.addView(label(message, 14, MUTED, false));
        content.addView(card);
    }

    private LinearLayout panel() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        view.setBackground(tint(Color.WHITE, 15));
        return view;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable tint(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private String joinSignals(List<String> signals) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < signals.size(); i++) {
            if (i > 0) result.append(" · ");
            result.append(signals.get(i));
        }
        return result.toString();
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        syncExecutor.shutdownNow();
        networkPool.shutdownNow();
        database.close();
        super.onDestroy();
    }

    private static final class FetchOutcome {
        final String market;
        final LocalDate date;
        final List<MarketData.Bar> bars;
        final Exception error;

        FetchOutcome(String market, LocalDate date, List<MarketData.Bar> bars, Exception error) {
            this.market = market;
            this.date = date;
            this.bars = bars;
            this.error = error;
        }
    }
}

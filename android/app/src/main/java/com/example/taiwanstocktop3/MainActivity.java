package com.example.taiwanstocktop3;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
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
    private static final String TAG = "TaiwanStockTop3";
    private static final String UPDATE_PREFS = "app-update-check";
    private static final String UPDATE_SUCCESSFUL_CHECKED_AT = "last-successful-check-time";
    private static final String UPDATE_ATTEMPTED_AT = "last-attempted-check-time";
    private static final long UPDATE_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final long UPDATE_RETRY_INTERVAL_MS = 15L * 60 * 1000;
    private static final String APPEARANCE_PREFS = "appearance";
    private static final String DARK_MODE_KEY = "dark-mode";

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService networkPool = Executors.newFixedThreadPool(4);
    private final ExecutorService updateCheckExecutor = Executors.newSingleThreadExecutor();
    private StockDatabase database;
    private LinearLayout root;
    private LinearLayout header;
    private LinearLayout content;
    private FrameLayout themeControl;
    private View themeSelectionIndicator;
    private TextView status;
    private TextView updateCheckLink;
    private TextView darkModeButton;
    private TextView lightModeButton;
    private Button refreshButton;
    private ProgressBar pullRefreshIndicator;
    private boolean syncing = false;
    private boolean checkingForUpdate = false;
    private boolean darkMode;

    private int HEADER;
    private int NAVY;
    private int GREEN;
    private int PAGE;
    private int CARD;
    private int MUTED;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("M/d (E)", Locale.TAIWAN);
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.TAIWAN);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        database = new StockDatabase(this);
        darkMode = getSharedPreferences(APPEARANCE_PREFS, MODE_PRIVATE)
                .getBoolean(DARK_MODE_KEY, false);
        updateThemeColors();
        applySystemBarColors();
        buildScreen();
        checkForAppUpdate(false);
        syncExecutor.execute(() -> {
            List<MarketData.DayRanking> cached = StockAnalyzer.rank(database.readAll());
            runOnUiThread(() -> displayRankings(cached));
        });
        syncMarkets();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);
        root.setPadding(0, dp(36), 0, 0);

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(16));
        header.setBackgroundColor(HEADER);
        header.setTag("theme-header");

        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("台股前三名", 23, Color.WHITE, true);
        TextView subtitle = label("15 項技術訊號 · 每項 10 分", 13, Color.rgb(203, 218, 224), false);
        titleStack.addView(title);
        titleStack.addView(subtitle);
        headingRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        themeControl = new FrameLayout(this);
        themeControl.setClipChildren(true);
        themeControl.setClipToPadding(true);
        themeControl.setBackground(glassTrackBackground());
        themeSelectionIndicator = new View(this);
        themeSelectionIndicator.setBackground(glassSelectionBackground(GREEN));
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(dp(54), dp(42),
                Gravity.TOP | Gravity.START);
        indicatorParams.leftMargin = dp(3);
        indicatorParams.topMargin = dp(3);
        themeControl.addView(themeSelectionIndicator, indicatorParams);

        LinearLayout themeOptions = new LinearLayout(this);
        themeOptions.setOrientation(LinearLayout.HORIZONTAL);
        themeOptions.setGravity(Gravity.CENTER);
        themeOptions.setPadding(dp(3), dp(3), dp(3), dp(3));
        darkModeButton = themeOption("深色", darkMode, true);
        lightModeButton = themeOption("淺色", !darkMode, false);
        themeOptions.addView(darkModeButton, new LinearLayout.LayoutParams(dp(54), dp(42)));
        themeOptions.addView(lightModeButton, new LinearLayout.LayoutParams(dp(54), dp(42)));
        themeControl.addView(themeOptions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        themeSelectionIndicator.setTranslationX(darkMode ? 0 : dp(54));
        headingRow.addView(themeControl, new LinearLayout.LayoutParams(dp(114), dp(48)));
        header.addView(headingRow);

        refreshButton = new Button(this);
        refreshButton.setText("更新資料");
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setAllCaps(false);
        refreshButton.setBackground(tint(GREEN, 18));
        refreshButton.setOnClickListener(view -> syncMarkets());
        refreshButton.setEnabled(!syncing);
        if (syncing) refreshButton.setText("更新中…");

        status = label("正在讀取手機內的行情紀錄…", 12, Color.rgb(214, 228, 231), false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        updateCheckLink = label("v" + BuildConfig.VERSION_NAME + " · 檢查更新", 11,
                Color.rgb(203, 218, 224), false);
        updateCheckLink.setClickable(true);
        updateCheckLink.setFocusable(true);
        updateCheckLink.setOnClickListener(view -> checkForAppUpdate(true));
        updateCheckLink.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout statusStack = new LinearLayout(this);
        statusStack.setOrientation(LinearLayout.VERTICAL);
        statusStack.addView(status);
        LinearLayout.LayoutParams updateLinkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updateLinkLp.topMargin = dp(6);
        statusStack.addView(updateCheckLink, updateLinkLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(statusStack, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusRow.addView(refreshButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams statusRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusRowLp.topMargin = dp(10);
        header.addView(statusRow, statusRowLp);
        root.addView(header);

        FrameLayout scrollFrame = new FrameLayout(this);
        PullRefreshScrollView scroll = new PullRefreshScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(22));
        content.setTag("theme-page");
        scroll.addView(content);
        scrollFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pullRefreshIndicator = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        pullRefreshIndicator.setIndeterminate(true);
        pullRefreshIndicator.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        pullRefreshIndicator.setVisibility(View.GONE);
        pullRefreshIndicator.setClickable(false);
        pullRefreshIndicator.setFocusable(false);
        pullRefreshIndicator.setElevation(dp(6));
        FrameLayout.LayoutParams pullIndicatorParams = new FrameLayout.LayoutParams(
                dp(30), dp(30), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pullIndicatorParams.topMargin = dp(10);
        scrollFrame.addView(pullRefreshIndicator, pullIndicatorParams);

        root.addView(scrollFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private TextView themeOption(String text, boolean selected, boolean darkSelection) {
        TextView option = label(text, 12, selected ? Color.WHITE : Color.rgb(203, 218, 224), selected);
        option.setGravity(Gravity.CENTER);
        option.setPadding(dp(4), 0, dp(4), 0);
        option.setBackgroundColor(Color.TRANSPARENT);
        option.setClickable(true);
        option.setFocusable(true);
        option.setContentDescription(darkSelection ? "切換深色模式" : "切換淺色模式");
        option.setOnClickListener(view -> setDarkMode(darkSelection));
        return option;
    }

    private void setDarkMode(boolean enabled) {
        if (darkMode == enabled) return;
        darkMode = enabled;
        getSharedPreferences(APPEARANCE_PREFS, MODE_PRIVATE).edit()
                .putBoolean(DARK_MODE_KEY, darkMode)
                .apply();
        updateThemeColors();
        applySystemBarColors();
        root.setBackgroundColor(PAGE);
        header.setBackgroundColor(HEADER);
        refreshButton.setBackground(tint(GREEN, 18));
        pullRefreshIndicator.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        updateThemeOption(darkModeButton, darkMode);
        updateThemeOption(lightModeButton, !darkMode);
        themeSelectionIndicator.setBackground(glassSelectionBackground(GREEN));
        animateThemeSelection(darkMode);
        applyThemeTextColors(root);
    }

    private void updateThemeOption(TextView option, boolean selected) {
        option.setTextColor(selected ? Color.WHITE : Color.rgb(203, 218, 224));
        option.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void animateThemeSelection(boolean selectDark) {
        themeSelectionIndicator.animate().cancel();
        themeSelectionIndicator.animate()
                .translationX(selectDark ? 0 : dp(54))
                .setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
    }

    private GradientDrawable glassTrackBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(170, 10, 24, 32), Color.argb(135, 28, 48, 58)});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.argb(105, 255, 255, 255));
        return background;
    }

    private GradientDrawable glassSelectionBackground(int accent) {
        int highlight = blendColor(accent, Color.WHITE, 0.42f);
        int middle = blendColor(accent, Color.WHITE, 0.12f);
        int shade = blendColor(accent, Color.BLACK, 0.18f);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(highlight, 238), withAlpha(middle, 218), withAlpha(shade, 205)});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.argb(175, 255, 255, 255));
        return background;
    }

    private int blendColor(int foreground, int background, float ratio) {
        float inverse = 1f - ratio;
        return Color.rgb(
                Math.round(Color.red(foreground) * inverse + Color.red(background) * ratio),
                Math.round(Color.green(foreground) * inverse + Color.green(background) * ratio),
                Math.round(Color.blue(foreground) * inverse + Color.blue(background) * ratio));
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void applyThemeTextColors(View view) {
        Object role = view.getTag();
        if ("theme-primary".equals(role) && view instanceof TextView) {
            ((TextView) view).setTextColor(NAVY);
        } else if ("theme-accent".equals(role) && view instanceof TextView) {
            ((TextView) view).setTextColor(GREEN);
        } else if ("theme-muted".equals(role) && view instanceof TextView) {
            ((TextView) view).setTextColor(MUTED);
        } else if ("theme-card".equals(role)) {
            view.setBackground(tint(CARD, 15));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyThemeTextColors(group.getChildAt(index));
            }
        }
    }

    private void updateThemeColors() {
        HEADER = Color.rgb(20, 43, 59);
        if (darkMode) {
            NAVY = Color.rgb(232, 238, 242);
            GREEN = Color.rgb(83, 184, 139);
            PAGE = Color.rgb(18, 23, 28);
            CARD = Color.rgb(35, 42, 48);
            MUTED = Color.rgb(171, 184, 192);
        } else {
            NAVY = Color.rgb(20, 43, 59);
            GREEN = Color.rgb(45, 126, 98);
            PAGE = Color.rgb(244, 246, 245);
            CARD = Color.WHITE;
            MUTED = Color.rgb(104, 119, 127);
        }
    }

    private void applySystemBarColors() {
        getWindow().setStatusBarColor(darkMode ? Color.rgb(11, 29, 40) : Color.WHITE);
        getWindow().setNavigationBarColor(PAGE);
        int visibility = getWindow().getDecorView().getSystemUiVisibility();
        int lightBarFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(darkMode
                ? visibility & ~lightBarFlags
                : visibility | lightBarFlags);
    }

    private void checkForAppUpdate(boolean manual) {
        if (checkingForUpdate) return;
        SharedPreferences preferences = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSuccessfulCheck = preferences.getLong(UPDATE_SUCCESSFUL_CHECKED_AT, 0L);
        long lastAttempt = preferences.getLong(UPDATE_ATTEMPTED_AT, 0L);
        if (!manual && isWithinInterval(now, lastSuccessfulCheck, UPDATE_CHECK_INTERVAL_MS)) return;
        if (!manual && isWithinInterval(now, lastAttempt, UPDATE_RETRY_INTERVAL_MS)) return;

        checkingForUpdate = true;
        preferences.edit().putLong(UPDATE_ATTEMPTED_AT, now).apply();
        updateCheckLink.setEnabled(false);
        if (manual) updateCheckLink.setText("正在檢查更新…");

        updateCheckExecutor.execute(() -> {
            AppUpdateChecker.Result result = null;
            IOException failure = null;
            try {
                result = AppUpdateChecker.check(BuildConfig.VERSION_NAME);
            } catch (IOException error) {
                failure = error;
            }
            AppUpdateChecker.Result checkedResult = result;
            IOException checkFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                checkingForUpdate = false;
                updateCheckLink.setEnabled(true);
                updateCheckLink.setText("v" + BuildConfig.VERSION_NAME + " · 檢查更新");
                if (checkFailure != null) {
                    Log.w(TAG, "Unable to check GitHub Releases", checkFailure);
                    if (manual) toast("目前無法連線檢查更新，請稍後再試。");
                    return;
                }
                if (checkedResult == null) return;
                getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit()
                        .putLong(UPDATE_SUCCESSFUL_CHECKED_AT, System.currentTimeMillis())
                        .apply();
                handleUpdateCheckResult(checkedResult, manual);
            });
        });
    }

    private static boolean isWithinInterval(long now, long timestamp, long interval) {
        return timestamp > 0 && now >= timestamp && now - timestamp < interval;
    }

    private void handleUpdateCheckResult(AppUpdateChecker.Result result, boolean manual) {
        if (result == null) return;
        switch (result.status) {
            case UPDATE_AVAILABLE:
                showUpdateDialog(result);
                break;
            case UP_TO_DATE:
                if (manual) toast("目前已是最新版本。");
                break;
            case NO_RELEASE:
                if (manual) toast("GitHub 還沒有發布可下載的 APK。");
                break;
            case NO_APK:
                if (manual) toast("最新版本尚未附上 APK 檔案。");
                break;
            case INVALID_VERSION:
                if (manual) toast("最新 Release 標籤格式無法辨識。");
                break;
            default:
                break;
        }
    }

    private void showUpdateDialog(AppUpdateChecker.Result update) {
        new AlertDialog.Builder(this)
                .setTitle("發現新版 v" + update.version)
                .setMessage("有新版 App 可下載。下載後請依 Android 提示確認安裝；更新需使用相同的 App 簽章。")
                .setPositiveButton("下載 APK", (dialog, which) -> openApkDownload(update.downloadUrl))
                .setNegativeButton("稍後", null)
                .show();
    }

    private void openApkDownload(String downloadUrl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Exception error) {
            Log.w(TAG, "Unable to open APK download URL", error);
            toast("無法開啟下載連結，請稍後再試。");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void syncMarkets() {
        if (syncing) return;
        syncing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText("更新中…");
        setStatus("正在更新行情…");
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
                    tasks.add(() -> download(fetchDate));
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
                        database.markSynced("TWSE", outcome.date);
                    } else {
                        failures++;
                    }
                } catch (Exception error) {
                    failures++;
                }
                done++;
                final int completedCount = done;
                final int taskCount = total;
                runOnUiThread(() -> setStatus("更新行情 " + completedCount + "/" + taskCount));
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
                pullRefreshIndicator.setVisibility(View.GONE);
                syncing = false;
            });
        });
    }

    private FetchOutcome download(LocalDate date) {
        try {
            List<MarketData.Bar> bars = ExchangeClient.fetchTwse(date);
            return new FetchOutcome(date, bars, null);
        } catch (Exception e) {
            return new FetchOutcome(date, new ArrayList<>(), e);
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
        addSectionTitle(latest.date.format(TITLE_DATE) + " 前三名",
                latest.qualifiedCount + " 檔符合 · 分數優先，同分看成交量");
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

        TextView note = label("詳細資料", 13, GREEN, true);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(4), dp(16), dp(4), dp(16));
        note.setClickable(true);
        note.setFocusable(true);
        note.setContentDescription("查看所有計分條件與排序規則");
        note.setOnClickListener(view -> showMethodDetails(note));
        content.addView(note);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(4), dp(6), dp(4), dp(8));
        TextView creator = label("製作者：Jack-0213", 10, MUTED, false);
        footer.addView(creator, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView disclaimerLink = label("免責聲明", 10, GREEN, false);
        disclaimerLink.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        disclaimerLink.setPadding(dp(8), dp(8), 0, dp(8));
        disclaimerLink.setClickable(true);
        disclaimerLink.setFocusable(true);
        disclaimerLink.setContentDescription("開啟免責聲明");
        disclaimerLink.setOnClickListener(view -> showDisclaimer(disclaimerLink));
        footer.addView(disclaimerLink);
        content.addView(footer);
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
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("查看" + name + "的達成條件");
        card.setOnClickListener(view -> showPickDetails(pick, card));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        content.addView(card, params);
    }

    private void showPickDetails(MarketData.Pick pick, View sourceCard) {
        LinearLayout floatingCard = createFloatingCard();

        String stockName = pick.name == null || pick.name.trim().isEmpty()
                ? pick.symbol : pick.name;
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.addView(label(stockName, 19, NAVY, true));
        titleStack.addView(label(pick.symbol + " · 第 " + pick.rank + " 名", 12, MUTED, false));
        heading.addView(titleStack, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(label(pick.score + "/150", 17, GREEN, true));
        floatingCard.addView(heading);

        TextView marketSummary = label(String.format(Locale.TAIWAN,
                "收盤 %.2f 元  ·  成交 %.0f 張", pick.close, pick.volumeLots), 12, MUTED, false);
        LinearLayout.LayoutParams marketSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        marketSummaryParams.topMargin = dp(7);
        floatingCard.addView(marketSummary, marketSummaryParams);

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(darkMode
                ? Color.argb(48, 255, 255, 255) : Color.argb(28, 20, 43, 59));
        LinearLayout.LayoutParams headerDividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        headerDividerParams.topMargin = dp(13);
        headerDividerParams.bottomMargin = dp(11);
        floatingCard.addView(headerDivider, headerDividerParams);

        LinearLayout conditionHeading = new LinearLayout(this);
        conditionHeading.setGravity(Gravity.CENTER_VERTICAL);
        conditionHeading.addView(label("達成條件", 14, NAVY, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        conditionHeading.addView(label(pick.signals.size() + " 項", 12, MUTED, false));
        floatingCard.addView(conditionHeading);

        ScrollView conditionsScroll = new ScrollView(this);
        conditionsScroll.setFillViewport(false);
        conditionsScroll.setVerticalScrollBarEnabled(true);
        LinearLayout conditionsList = new LinearLayout(this);
        conditionsList.setOrientation(LinearLayout.VERTICAL);
        if (pick.signals.isEmpty()) {
            TextView empty = label("目前沒有達成條件。", 13, MUTED, false);
            empty.setPadding(0, dp(12), 0, dp(8));
            conditionsList.addView(empty);
        } else {
            for (int index = 0; index < pick.signals.size(); index++) {
                LinearLayout conditionRow = new LinearLayout(this);
                conditionRow.setGravity(Gravity.CENTER_VERTICAL);
                conditionRow.setPadding(0, dp(7), 0, dp(7));
                conditionRow.addView(label("✓", 14, GREEN, true),
                        new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
                conditionRow.addView(label(pick.signals.get(index), 14, NAVY, false),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                conditionsList.addView(conditionRow);
                if (index < pick.signals.size() - 1) {
                    View rowDivider = new View(this);
                    rowDivider.setBackgroundColor(darkMode
                            ? Color.argb(30, 255, 255, 255) : Color.argb(22, 20, 43, 59));
                    conditionsList.addView(rowDivider, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        }
        conditionsScroll.addView(conditionsList);
        LinearLayout.LayoutParams conditionsScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(380), Math.max(dp(72), dp(pick.signals.size() * 42 + 8))));
        conditionsScrollParams.topMargin = dp(3);
        floatingCard.addView(conditionsScroll, conditionsScrollParams);

        TextView closeHint = label("點擊模糊處關閉", 11, MUTED, false);
        closeHint.setGravity(Gravity.END);
        LinearLayout.LayoutParams closeHintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeHintParams.topMargin = dp(8);
        floatingCard.addView(closeHint, closeHintParams);

        showFloatingOverlay(sourceCard, floatingCard);
    }

    private void showMethodDetails(View sourceView) {
        LinearLayout floatingCard = createFloatingCard();
        floatingCard.addView(label("詳細資料", 19, NAVY, true));
        TextView scoreSummary = label("15 項技術指標，每符合 1 項加 10 分，最高 150 分。",
                13, MUTED, false);
        LinearLayout.LayoutParams scoreSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scoreSummaryParams.topMargin = dp(5);
        floatingCard.addView(scoreSummary, scoreSummaryParams);

        ScrollView detailsScroll = new ScrollView(this);
        detailsScroll.setFillViewport(false);
        detailsScroll.setVerticalScrollBarEnabled(true);
        LinearLayout detailsList = new LinearLayout(this);
        detailsList.setOrientation(LinearLayout.VERTICAL);

        addMethodSection(detailsList, "入選門檻");
        addMethodBullet(detailsList, "至少符合 8 項條件（80 分）");
        addMethodBullet(detailsList, "收盤價高於 10 元");
        addMethodBullet(detailsList, "成交量至少 2,000 張");
        addMethodBullet(detailsList, "至少有 70 個交易日資料才會計算");
        addMethodBullet(detailsList, "符合門檻後取分數前三名；同分時成交量較大者優先");

        addMethodSection(detailsList, "15 項計分條件（每項 10 分）");
        String[][] rules = {
                {"收盤高於 20 日線", "當日收盤價 > 20 日平均收盤價"},
                {"20 日線高於 60 日線", "20 日均線 > 60 日均線"},
                {"60 日線上彎", "目前 60 日均線 > 10 個交易日前的 60 日均線"},
                {"20 日線上彎", "目前 20 日均線 > 5 個交易日前的 20 日均線"},
                {"MACD 多方", "MACD 線（EMA12 − EMA26）> 訊號線"},
                {"MACD 動能增強", "今日 MACD 柱狀體 > 前一交易日"},
                {"RSI 50 至 70", "14 日 RSI 介於 50 到 70，含上下限"},
                {"KD 多方且未過熱", "K 值 > D 值，且 K 值 < 80"},
                {"突破 20 日高點", "收盤價 > 前 20 個交易日最高價"},
                {"突破 60 日高點", "收盤價 > 前 60 個交易日最高價"},
                {"成交量放大", "今日成交量 > 前 20 日均量的 1.2 倍"},
                {"OBV 偏多", "今日 OBV > 近 20 個交易日 OBV 均值"},
                {"MFI 偏多", "14 日 MFI > 50"},
                {"ADX 趨勢偏多", "ADX > 20，且 +DI > −DI"},
                {"20 日報酬為正", "今日收盤價 > 20 個交易日前收盤價"}
        };
        for (int index = 0; index < rules.length; index++) {
            addMethodRule(detailsList, index + 1, rules[index][0], rules[index][1]);
        }

        detailsScroll.addView(detailsList);
        int maxScrollHeight = Math.min(dp(430),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f));
        LinearLayout.LayoutParams detailsScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight);
        detailsScrollParams.topMargin = dp(10);
        floatingCard.addView(detailsScroll, detailsScrollParams);

        TextView closeHint = label("點擊模糊處關閉", 11, MUTED, false);
        closeHint.setGravity(Gravity.END);
        LinearLayout.LayoutParams closeHintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeHintParams.topMargin = dp(8);
        floatingCard.addView(closeHint, closeHintParams);
        showFloatingOverlay(sourceView, floatingCard);
    }

    private void showDisclaimer(View sourceView) {
        LinearLayout floatingCard = createFloatingCard();
        floatingCard.addView(label("免責聲明", 19, NAVY, true));
        TextView disclaimer = label(
                "本 App 依公開行情與技術條件產生排名，僅供資訊參考，不保證獲利。買賣決策及投資風險由使用者自行評估並承擔。",
                14, NAVY, false);
        disclaimer.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams disclaimerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        disclaimerParams.topMargin = dp(12);
        floatingCard.addView(disclaimer, disclaimerParams);

        TextView closeHint = label("點擊模糊處關閉", 11, MUTED, false);
        closeHint.setGravity(Gravity.END);
        LinearLayout.LayoutParams closeHintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeHintParams.topMargin = dp(8);
        floatingCard.addView(closeHint, closeHintParams);
        showFloatingOverlay(sourceView, floatingCard);
    }

    private LinearLayout createFloatingCard() {
        LinearLayout floatingCard = new LinearLayout(this);
        floatingCard.setOrientation(LinearLayout.VERTICAL);
        floatingCard.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable floatingBackground = tint(CARD, 22);
        floatingBackground.setStroke(dp(1), darkMode
                ? Color.argb(75, 255, 255, 255) : Color.argb(38, 20, 43, 59));
        floatingCard.setBackground(floatingBackground);
        floatingCard.setElevation(dp(20));
        floatingCard.setClickable(true);
        floatingCard.setFocusable(true);
        floatingCard.setOnClickListener(view -> { });
        return floatingCard;
    }

    private void addMethodSection(LinearLayout parent, String title) {
        TextView section = label(title, 14, NAVY, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        parent.addView(section, params);
    }

    private void addMethodBullet(LinearLayout parent, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.addView(label("•", 14, GREEN, true),
                new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(label(text, 12, NAVY, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
    }

    private void addMethodRule(LinearLayout parent, int number, String title, String formula) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView index = label(String.format(Locale.TAIWAN, "%02d", number), 12, GREEN, true);
        row.addView(index, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout textStack = new LinearLayout(this);
        textStack.setOrientation(LinearLayout.VERTICAL);
        textStack.addView(label(title + " · 10 分", 13, NAVY, true));
        textStack.addView(label(formula, 11, MUTED, false));
        row.addView(textStack, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(darkMode
                ? Color.argb(30, 255, 255, 255) : Color.argb(22, 20, 43, 59));
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void showFloatingOverlay(View sourceView, LinearLayout floatingCard) {
        Dialog dialog = new Dialog(this);
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(darkMode
                ? Color.argb(138, 0, 0, 0) : Color.argb(112, 8, 21, 30));
        overlay.setClickable(true);
        overlay.setAlpha(0f);
        FrameLayout.LayoutParams floatingParams = new FrameLayout.LayoutParams(
                Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(36)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(floatingCard, floatingParams);
        boolean[] closing = {false};
        overlay.setOnClickListener(view -> {
            if (closing[0]) return;
            closing[0] = true;
            floatingCard.animate().cancel();
            overlay.animate().cancel();
            floatingCard.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(150).start();
            overlay.animate().alpha(0f).setDuration(170)
                    .withEndAction(dialog::dismiss).start();
        });

        dialog.setContentView(overlay);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(ignored -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                root.setRenderEffect(null);
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            root.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    dp(12), dp(12), android.graphics.Shader.TileMode.CLAMP));
        }
        overlay.post(() -> {
            int[] sourcePosition = new int[2];
            int[] overlayPosition = new int[2];
            sourceView.getLocationOnScreen(sourcePosition);
            overlay.getLocationOnScreen(overlayPosition);
            float startX = sourcePosition[0] + sourceView.getWidth() / 2f
                    - overlayPosition[0] - overlay.getWidth() / 2f;
            float startY = sourcePosition[1] + sourceView.getHeight() / 2f
                    - overlayPosition[1] - overlay.getHeight() / 2f;
            floatingCard.setTranslationX(startX);
            floatingCard.setTranslationY(startY);
            floatingCard.setScaleX(0.78f);
            floatingCard.setScaleY(0.78f);
            overlay.animate().alpha(1f).setDuration(180).start();
            floatingCard.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
        });
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
        view.setBackground(tint(CARD, 15));
        view.setTag("theme-card");
        return view;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (color == NAVY) view.setTag("theme-primary");
        else if (color == GREEN) view.setTag("theme-accent");
        else if (color == MUTED) view.setTag("theme-muted");
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable tint(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class PullRefreshScrollView extends ScrollView {
        private float startY;
        private boolean refreshGestureCaptured;

        PullRefreshScrollView(Activity context) {
            super(context);
            setFillViewport(true);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = event.getY();
                    refreshGestureCaptured = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!syncing && !refreshGestureCaptured && getScrollY() == 0
                            && event.getY() - startY >= dp(48)) {
                        refreshGestureCaptured = true;
                        pullRefreshIndicator.setVisibility(View.VISIBLE);
                        syncMarkets();
                        return true;
                    }
                    if (refreshGestureCaptured) return true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    refreshGestureCaptured = false;
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && !syncing && !refreshGestureCaptured && getScrollY() == 0
                    && event.getY() - startY >= dp(48)) {
                // ScrollView can intercept the drag before it reaches the pull threshold.
                // Check again here so the refresh still triggers after interception.
                refreshGestureCaptured = true;
                pullRefreshIndicator.setVisibility(View.VISIBLE);
                syncMarkets();
                return true;
            }
            if (refreshGestureCaptured) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    refreshGestureCaptured = false;
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    @Override protected void onDestroy() {
        syncExecutor.shutdownNow();
        networkPool.shutdownNow();
        updateCheckExecutor.shutdownNow();
        database.close();
        super.onDestroy();
    }

    private static final class FetchOutcome {
        final LocalDate date;
        final List<MarketData.Bar> bars;
        final Exception error;

        FetchOutcome(LocalDate date, List<MarketData.Bar> bars, Exception error) {
            this.date = date;
            this.bars = bars;
            this.error = error;
        }
    }
}

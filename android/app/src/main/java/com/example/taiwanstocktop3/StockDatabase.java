package com.example.taiwanstocktop3;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class StockDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "tw_stock_history.db";
    private static final int DB_VERSION = 3;

    StockDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bars (symbol TEXT NOT NULL, name TEXT, market TEXT, date TEXT NOT NULL, "
                + "open REAL, high REAL, low REAL, close REAL, volume REAL, "
                + "PRIMARY KEY(symbol,date))");
        db.execSQL("CREATE INDEX bars_date_idx ON bars(date)");
        db.execSQL("CREATE TABLE sync_state (market TEXT NOT NULL, date TEXT NOT NULL, "
                + "PRIMARY KEY(market,date))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // Keep the app's history and rankings limited to TWSE-listed stocks.
            db.delete("bars", "market=?", new String[]{"TPEx"});
            db.delete("sync_state", "market=?", new String[]{"TPEx"});
        }
    }

    synchronized void saveBars(List<MarketData.Bar> bars) {
        if (bars == null || bars.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (MarketData.Bar bar : bars) {
                ContentValues values = new ContentValues();
                values.put("symbol", bar.symbol);
                values.put("name", bar.name);
                values.put("market", bar.market);
                values.put("date", bar.date.toString());
                values.put("open", bar.open);
                values.put("high", bar.high);
                values.put("low", bar.low);
                values.put("close", bar.close);
                values.put("volume", bar.volume);
                db.insertWithOnConflict("bars", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized List<MarketData.Bar> readAll() {
        ArrayList<MarketData.Bar> bars = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT symbol,name,market,date,open,high,low,close,volume FROM bars ORDER BY symbol,date", null)) {
            while (cursor.moveToNext()) {
                bars.add(new MarketData.Bar(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        LocalDate.parse(cursor.getString(3)), cursor.getDouble(4), cursor.getDouble(5),
                        cursor.getDouble(6), cursor.getDouble(7), cursor.getDouble(8)));
            }
        }
        return bars;
    }

    synchronized int distinctTradingDays() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(DISTINCT date) FROM bars", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    synchronized LocalDate newestDate() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT MAX(date) FROM bars", null)) {
            return cursor.moveToFirst() && cursor.getString(0) != null
                    ? LocalDate.parse(cursor.getString(0)) : null;
        }
    }

    synchronized boolean wasSynced(String market, LocalDate date) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sync_state WHERE market=? AND date=?",
                new String[]{market, date.toString()})) {
            return cursor.moveToFirst();
        }
    }

    synchronized void markSynced(String market, LocalDate date) {
        ContentValues values = new ContentValues();
        values.put("market", market);
        values.put("date", date.toString());
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void trimBefore(LocalDate date) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("bars", "date < ?", new String[]{date.toString()});
        db.delete("sync_state", "date < ?", new String[]{date.toString()});
    }
}

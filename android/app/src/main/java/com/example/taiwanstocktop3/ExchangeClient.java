package com.example.taiwanstocktop3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ExchangeClient {
    private static final DateTimeFormatter TWSE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TPEX_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US);

    private ExchangeClient() {}

    static List<MarketData.Bar> fetchTwse(LocalDate date) throws IOException {
        String url = "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date="
                + TWSE_DATE.format(date) + "&type=ALLBUT0999&response=json";
        return fetch(url, "TWSE", date, "https://www.twse.com.tw/");
    }

    static List<MarketData.Bar> fetchTpex(LocalDate date) throws IOException {
        String url = "https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?response=json&date="
                + TPEX_DATE.format(date);
        return fetch(url, "TPEx", date, "https://www.tpex.org.tw/");
    }

    private static List<MarketData.Bar> fetch(String address, String market,
                                               LocalDate requestedDate, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        connection.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36");
        connection.setRequestProperty("Referer", referer);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readBody(stream);
            if (status >= 400) throw new IOException(market + " 回傳 HTTP " + status);
            if (body.isEmpty()) throw new IOException(market + " 回傳空白內容");
            JSONObject root = new JSONObject(body);
            JSONArray tables = root.optJSONArray("tables");
            if (tables == null) {
                JSONArray singleTable = new JSONArray();
                singleTable.put(root);
                tables = singleTable;
            }
            ArrayList<MarketData.Bar> bars = new ArrayList<>();
            boolean foundQuoteTable = false;
            for (int i = 0; i < tables.length(); i++) {
                JSONObject table = tables.optJSONObject(i);
                if (table == null) continue;
                JSONArray headers = firstArray(table, "fields", "Field", "field", "headers");
                JSONArray rows = firstArray(table, "data", "aaData", "rows");
                if (headers == null || rows == null) continue;
                int codeIndex = findHeader(headers, "證券代號", "股票代號", "代號", "SecuritiesCompanyCode", "Code");
                int nameIndex = findHeader(headers, "證券名稱", "股票名稱", "名稱", "Name");
                int openIndex = findHeader(headers, "開盤價", "開盤", "Open");
                int highIndex = findHeader(headers, "最高價", "最高", "High");
                int lowIndex = findHeader(headers, "最低價", "最低", "Low");
                int closeIndex = findHeader(headers, "收盤價", "收盤", "Close");
                int volumeIndex = findHeader(headers, "成交股數", "成交量(股)", "成交量", "TradingShares", "Volume");
                if (codeIndex < 0 || closeIndex < 0 || volumeIndex < 0) continue;
                foundQuoteTable = true;

                for (int rowNumber = 0; rowNumber < rows.length(); rowNumber++) {
                    JSONArray row = rows.optJSONArray(rowNumber);
                    if (row == null || row.length() <= Math.max(codeIndex, volumeIndex)) continue;
                    String code = cell(row, codeIndex).trim();
                    String name = nameIndex >= 0 ? cell(row, nameIndex).trim() : code;
                    if (!isCommonStock(code, name)) continue;
                    Double close = number(row, closeIndex);
                    Double volume = number(row, volumeIndex);
                    Double open = number(row, openIndex);
                    Double high = number(row, highIndex);
                    Double low = number(row, lowIndex);
                    if (close == null || volume == null || close <= 0 || volume <= 0) continue;
                    if (open == null) open = close;
                    if (high == null) high = Math.max(open, close);
                    if (low == null) low = Math.min(open, close);
                    bars.add(new MarketData.Bar(code, name, market, requestedDate,
                            open, high, low, close, volume));
                }
            }
            if (!foundQuoteTable) {
                String message = root.optString("stat", root.optString("message", ""));
                String lower = message.toLowerCase(Locale.ROOT);
                boolean noTradingData = message.contains("沒有符合") || message.contains("查無資料")
                        || message.contains("無資料") || lower.contains("no data") || lower.contains("nodata");
                if (!noTradingData) throw new IOException(market + " 回傳的行情欄位格式不符");
            }
            return bars;
        } catch (org.json.JSONException e) {
            throw new IOException(market + " 行情格式無法解析", e);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isCommonStock(String code, String name) {
        if (code == null || !code.matches("[0-9]{4,6}")) return false;
        String upperName = name == null ? "" : name.toUpperCase(Locale.ROOT);
        return !code.startsWith("00") && !upperName.contains("ETF") && !upperName.contains("ETN");
    }

    private static JSONArray firstArray(JSONObject object, String... names) {
        for (String name : names) {
            JSONArray array = object.optJSONArray(name);
            if (array != null) return array;
        }
        return null;
    }

    private static int findHeader(JSONArray headers, String... aliases) {
        for (int i = 0; i < headers.length(); i++) {
            String header = headers.optString(i, "").replace(" ", "").trim();
            for (String alias : aliases) {
                if (header.equalsIgnoreCase(alias) || header.contains(alias)) return i;
            }
        }
        return -1;
    }

    private static String cell(JSONArray row, int index) {
        if (index < 0 || index >= row.length()) return "";
        Object value = row.opt(index);
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
    }

    private static Double number(JSONArray row, int index) {
        String value = cell(row, index).replace(",", "").replace("−", "-").trim();
        if (value.isEmpty() || value.equals("-") || value.equals("--") || value.equals("X")) return null;
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }
}

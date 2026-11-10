package com.example.taiwanstocktop3;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AppUpdateChecker {
    private static final String RELEASE_API =
            "https://api.github.com/repos/Jack-02-13/Taiwan-Stock-Signal-Ranker/releases/latest";
    private static final String ASSET_PATH_PREFIX =
            "/Jack-02-13/Taiwan-Stock-Signal-Ranker/releases/download/";
    private static final String RELEASE_APK_NAME = "app-release.apk";

    enum Status {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        NO_RELEASE,
        NO_APK,
        INVALID_VERSION
    }

    static final class Result {
        final Status status;
        final String version;
        final String downloadUrl;

        Result(Status status, String version, String downloadUrl) {
            this.status = status;
            this.version = version;
            this.downloadUrl = downloadUrl;
        }
    }

    private AppUpdateChecker() {}

    static Result check(String currentVersion) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(9000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "TaiwanStockTop3-Android");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return new Result(Status.NO_RELEASE, null, null);
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub 回應 HTTP " + responseCode);
            }

            JSONObject release = new JSONObject(readBody(connection.getInputStream()));
            String tag = release.optString("tag_name", "").trim();
            String releaseVersion = normalizeVersion(tag);
            String installedVersion = normalizeVersion(currentVersion);
            if (releaseVersion == null || installedVersion == null) {
                return new Result(Status.INVALID_VERSION, tag, null);
            }
            if (compareVersions(releaseVersion, installedVersion) <= 0) {
                return new Result(Status.UP_TO_DATE, releaseVersion, null);
            }

            String apkUrl = findApkUrl(release.optJSONArray("assets"));
            if (apkUrl == null) return new Result(Status.NO_APK, releaseVersion, null);
            return new Result(Status.UPDATE_AVAILABLE, releaseVersion, apkUrl);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("GitHub Release 資料格式無法讀取。", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String findApkUrl(JSONArray assets) {
        if (assets == null) return null;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (!RELEASE_APK_NAME.equalsIgnoreCase(name)) continue;
            String url = asset.optString("browser_download_url", "");
            if (isReleaseApkUrl(url)) return url;
        }
        return null;
    }

    private static boolean isReleaseApkUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith(ASSET_PATH_PREFIX)
                    && uri.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String normalizeVersion(String value) {
        if (value == null) return null;
        String version = value.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        return version.matches("\\d+(\\.\\d+)*") ? version : null;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            long leftPart = index < leftParts.length ? Long.parseLong(leftParts[index]) : 0;
            long rightPart = index < rightParts.length ? Long.parseLong(rightParts[index]) : 0;
            int comparison = Long.compare(leftPart, rightPart);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static String readBody(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}

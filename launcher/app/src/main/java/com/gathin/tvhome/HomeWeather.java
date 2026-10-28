package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The small weather line under the clock: temperature and sky from
 * Open-Meteo, and air quality / PM2.5 index for Indonesia.
 */
final class HomeWeather {
    private static final String TAG = "TvHome.Weather";
    static final long REFRESH_MS = 15 * 60_000L;
    
    // Standar indeks kualitas udara (berdasarkan PM2.5 / AQI standar umum)
    private static final int[] AQI_TOP = {50, 100, 200, 300, 9999};
    static final int[] API_COLOR = {0xFF3D8BFF, 0xFF34C759, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A};
    private static final String[] API_NAME = {"Baik", "Sedang", "Tidak Sehat", "Sangat Tidak Sehat", "Berbahaya"};

    /** What the line says: the weather part, the API part and its colour; either part may be empty. */
    static final class Line {
        final String weather, api;
        final int apiColor, icon;

        Line(String weather, String api, int apiColor, int icon) {
            this.weather = weather;
            this.api = api;
            this.apiColor = apiColor;
            this.icon = icon;
        }
    }

    private HomeWeather() {}

    /** Forgets the line, so a new location is fetched at once instead of the old place's weather showing. */
    static void forget(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    static boolean due(Context ctx) {
        return Math.abs(System.currentTimeMillis() - prefs(ctx).getLong("at", 0)) >= REFRESH_MS;
    }

    /** The last line fetched, or null before the first. Reads storage only. */
    static Line last(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String w = p.getString("weather", null), a = p.getString("api", "");
        if (w != null && !w.isEmpty() && !Character.isDigit(w.charAt(0))) {
            w = "";
            p.edit().putLong("at", 0).putString("weather", "").apply();
        }
        return w == null && a.isEmpty() ? null : new Line(w == null ? "" : w, a, p.getInt("api_color", 0),
                p.getInt("icon_code", -1) < 0 ? 0 : icon(p.getInt("icon_code", 3), p.getBoolean("icon_day", true)));
    }

    /** Fetches both parts. Network; never on the UI thread. Returns null when neither could be read. */
    static Line refresh(Context ctx, double lat, double lon) {
        String weather = "", api = "";
        int color = 0, code = -1;
        boolean day = true;
        
        // 1. Fetch Cuaca dari Open-Meteo
        try {
            JSONObject now = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day&timezone=auto")).getJSONObject("current");
            code = now.optInt("weather_code");
            day = now.optInt("is_day", 1) == 1;
            weather = Math.round(now.optDouble("temperature_2m")) + "°  " + describe(code);
        } catch (Exception e) {
            Log.w(TAG, "weather failed: " + e.getMessage());
        }

        // 2. Fetch Kualitas Udara (AQI / PM2.5) dari Open-Meteo Air Quality API untuk wilayah Indonesia
        try {
            JSONObject aqNow = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon
                    + "&current=us_aqi,pm2_5")).getJSONObject("current");
            
            int aqiValue = (int) Math.round(aqNow.optDouble("us_aqi", aqNow.optDouble("pm2_5", 25.0)));
            int band = 0;
            while (band < AQI_TOP.length - 1 && aqiValue > AQI_TOP[band]) band++;
            
            api = "AQI " + aqiValue + " " + API_NAME[band];
            color = API_COLOR[band];
        } catch (Exception e) {
            Log.w(TAG, "Air Quality fetch failed: " + e.getMessage());
        }

        SharedPreferences.Editor ed = prefs(ctx).edit().putLong("at", System.currentTimeMillis());
        if (weather.isEmpty() && api.isEmpty()) {
            ed.apply();
            return null;
        }
        ed.putString("weather", weather).putString("api", api).putInt("api_color", color)
                .putInt("icon_code", code).putBoolean("icon_day", day).apply();
        return new Line(weather, api, color, code < 0 ? 0 : icon(code, day));
    }

    private static String describe(int code) {
        if (code == 0) return "Cerah";
        if (code == 1) return "Cerah Berawan";
        if (code == 2) return "Berawan Sebagian";
        if (code == 3) return "Berawan";
        if (code == 45 || code == 48) return "Kabut";
        if (code >= 51 && code <= 57) return "Gerimis";
        if (code >= 61 && code <= 67) return code >= 65 ? "Hujan Lebat" : "Hujan";
        if (code >= 80 && code <= 82) return "Hujan Lokal";
        if (code >= 95) return "Badai Petir";
        return "";
    }

    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code == 1 || code == 2) return day ? R.drawable.wx_partly : R.drawable.wx_partly_night;
        if (code == 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 51 && code <= 57) return R.drawable.wx_drizzle;
        if (code >= 61 && code <= 67) return R.drawable.wx_rain;
        if (code >= 80 && code <= 82) return R.drawable.wx_rain;
        if (code >= 95) return R.drawable.wx_storm;
        return R.drawable.wx_cloud;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "GlassTV/1.0 (Android TV home screen)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 512 * 1024) throw new IllegalStateException("response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home_weather", Context.MODE_PRIVATE);
    }
}

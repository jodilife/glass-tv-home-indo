package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Clean Jadwal Shalat for Indonesia (Kemenag standard).
 * Fetches official timetable, caches locally, and includes
 * an accurate solar-calculation fallback so it never fails offline.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String PREFS_NAME = "prayer_times_cache";
    private static final String KEY_JSON = "takwim_indonesia";
    private static final String KEY_MONTH = "takwim_month";

    // Defaults to Jakarta (ID code: 1301)
    private static final String DEFAULT_KOTA_ID = "1301";
    private static final double TIMEZONE = 7.0; // WIB default

    static final String[] NAMES = {"Subuh", "Syuruk", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final String[] PRAYER_NAMES = NAMES;
    private static final String[] PRAYER_KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};

    static final class Day {
        final int[] minutes;
        final String hijri;

        Day(int[] minutes, String hijri) {
            this.minutes = minutes;
            this.hijri = hijri;
        }
    }

    static Day day(Context ctx, String zone, Calendar date) {
        if (zone == null || zone.isEmpty()) zone = getActiveKotaId(ctx);
        long[] times = getTimesForToday(ctx, date.get(Calendar.DAY_OF_MONTH));
        if (times == null || times.length < 6) {
            times = calculateFallback(ctx, date);
        }
        if (times == null || times.length < 6) return null;
        int[] minutes = new int[6];
        for (int i = 0; i < 6; i++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(times[i]);
            minutes[i] = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        }
        return new Day(minutes, getHijriDate());
    }

    static boolean due(Context ctx, String zone) {
        return day(ctx, zone, Calendar.getInstance()) == null;
    }

    static boolean refresh(Context ctx, String zone) {
        sync(ctx, null);
        return true;
    }

    private PrayerTimes() {
    }

    /** Forces an immediate background sync for the active location/city. */
    static void sync(Context context, Runnable onUpdated) {
        syncInternal(context, onUpdated, true);
    }

    /** Refresh timetable in background if needed (once a month / day). */
    static void syncIfDue(Context context, Runnable onUpdated) {
        syncInternal(context, onUpdated, false);
    }

    private static void syncInternal(Context context, Runnable onUpdated, boolean force) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                Calendar now = Calendar.getInstance();
                int currentMonth = now.get(Calendar.YEAR) * 100 + (now.get(Calendar.MONTH) + 1);
                String kotaId = getActiveKotaId(context);
                String cachedKota = prefs.getString("cached_kota", "");

                if (!force && kotaId.equals(cachedKota) && prefs.getInt(KEY_MONTH, 0) == currentMonth && prefs.contains(KEY_JSON)) {
                    return; // already have this month's schedule
                }

                int year = now.get(Calendar.YEAR);
                int month = now.get(Calendar.MONTH) + 1;
                
                // Endpoint API Kemenag via MyQuran API
                URL url = new URL("https://api.myquran.com/v1/sholat/jadwal/" + kotaId + "/" + year + "/" + month);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    // Parse format MyQuran ke struktur JSON internal launcher
                    JSONObject responseObj = new JSONObject(sb.toString());
                    if (responseObj.optBoolean("status", false)) {
                        JSONObject dataObj = responseObj.getJSONObject("data");
                        JSONArray jadwalArr = dataObj.getJSONArray("jadwal");
                        
                        JSONArray convertedPrayers = new JSONArray();
                        for (int i = 0; i < jadwalArr.length(); i++) {
                            JSONObject dayItem = jadwalArr.getJSONObject(i);
                            int dayNum = dayItem.optInt("tanggal_int", i + 1);
                            
                            // Format tanggal string ke timestamp millis untuk hari tersebut
                            Calendar dayCal = Calendar.getInstance();
                            dayCal.set(Calendar.YEAR, year);
                            dayCal.set(Calendar.MONTH, month - 1);
                            dayCal.set(Calendar.DAY_OF_MONTH, i + 1);
                            long baseDayMillis = dayCal.getTimeInMillis() - (dayCal.get(Calendar.HOUR_OF_DAY) * 3600000L + dayCal.get(Calendar.MINUTE) * 60000L);

                            JSONObject pObj = new JSONObject();
                            pObj.put("day", i + 1);
                            pObj.put("fajr", parseTimeToSeconds(baseDayMillis, dayItem.optString("subuh")));
                            pObj.put("syuruk", parseTimeToSeconds(baseDayMillis, dayItem.optString("terbit")));
                            pObj.put("dhuhr", parseTimeToSeconds(baseDayMillis, dayItem.optString("dzuhur")));
                            pObj.put("asr", parseTimeToSeconds(baseDayMillis, dayItem.optString("ashar")));
                            pObj.put("maghrib", parseTimeToSeconds(baseDayMillis, dayItem.optString("maghrib")));
                            pObj.put("isha", parseTimeToSeconds(baseDayMillis, dayItem.optString("isya")));
                            
                            convertedPrayers.put(pObj);
                        }

                        JSONObject finalCacheObj = new JSONObject();
                        finalCacheObj.put("prayers", convertedPrayers);

                        prefs.edit()
                                .putString(KEY_JSON, finalCacheObj.toString())
                                .putInt(KEY_MONTH, currentMonth)
                                .putString("cached_kota", kotaId)
                                .apply();

                        if (onUpdated != null) {
                            new Handler(Looper.getMainLooper()).post(onUpdated);
                        }
                        Log.i(TAG, "Synced prayer times for Indonesian city ID " + kotaId + " successfully");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Prayer sync offline/failed: " + e.getMessage());
            }
        }, "prayer-sync").start();
    }

    private static long parseTimeToSeconds(long baseDayMillis, String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            return (baseDayMillis / 1000L) + (h * 3600L) + (m * 60L);
        } catch (Exception e) {
            return baseDayMillis / 1000L;
        }
    }

    public static class PrayerZone {
        public final String code;
        public final String state;
        public final String name;
        public final double lat;
        public final double lon;

        public PrayerZone(String code, String state, String name, double lat, double lon) {
            this.code = code;
            this.state = state;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }

        public String getDisplayName() {
            return name + " (" + state + " • " + code + ")";
        }
    }

    private static final List<PrayerZone> PRESET_ZONES = new ArrayList<>();

    static {
        // Daftar Kota / Kabupaten Utama di Indonesia (Kemenag IDs)
        PRESET_ZONES.add(new PrayerZone("1301", "DKI Jakarta", "Jakarta Pusat / DKI Jakarta", -6.2088, 106.8456));
        PRESET_ZONES.add(new PrayerZone("1502", "Jawa Timur", "Surabaya", -7.2575, 112.7521));
        PRESET_ZONES.add(new PrayerZone("1218", "Jawa Barat", "Bandung", -6.9175, 107.6191));
        PRESET_ZONES.add(new PrayerZone("0201", "Sumatera Utara", "Medan", 3.5952, 98.6722));
        PRESET_ZONES.add(new PrayerZone("2201", "Bali", "Denpasar", -8.6705, 115.2126));
        PRESET_ZONES.add(new PrayerZone("0401", "Sumatera Barat", "Padang", -0.9471, 100.4172));
        PRESET_ZONES.add(new PrayerZone("0101", "Aceh", "Banda Aceh", 5.5483, 95.3238));
        PRESET_ZONES.add(new PrayerZone("1221", "Jawa Barat", "Bekasi", -6.2383, 106.9756));
        PRESET_ZONES.add(new PrayerZone("1513", "Jawa Timur", "Malang", -7.9666, 112.6326));
        PRESET_ZONES.add(new PrayerZone("1401", "D.I. Yogyakarta", "Yogyakarta", -7.7956, 110.3695));
        PRESET_ZONES.add(new PrayerZone("1901", "Kalimantan Selatan", "Banjarmasin", -3.3194, 114.5905));
        PRESET_ZONES.add(new PrayerZone("2701", "Sulawesi Selatan", "Makassar", -5.1477, 119.4327));
        PRESET_ZONES.add(new PrayerZone("3101", "Sumatera Selatan", "Palembang", -2.9761, 104.7754));
    }

    public static List<PrayerZone> getPresetZones() {
        return Collections.unmodifiableList(PRESET_ZONES);
    }

    public static String getActiveZone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains("manual_prayer_zone")) {
            return prefs.getString("manual_prayer_zone", DEFAULT_KOTA_ID);
        }
        return getActiveKotaId(context);
    }

    public static String getActiveZoneName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains("manual_prayer_zone_name")) {
            return prefs.getString("manual_prayer_zone_name", "Jakarta");
        }
        String zone = getActiveZone(context);
        for (PrayerZone pz : PRESET_ZONES) {
            if (pz.code.equalsIgnoreCase(zone)) return pz.name;
        }
        return AppLocationManager.getLocationName(context);
    }

    public static String getShortZoneName(Context context) {
        String name = getActiveZoneName(context);
        if (name == null || name.trim().isEmpty()) return "Jakarta";
        int cut = name.indexOf('/');
        if (cut > 0) name = name.substring(0, cut);
        cut = name.indexOf(',');
        if (cut > 0) name = name.substring(0, cut);
        name = name.trim();
        return name.isEmpty() ? "Jakarta" : name;
    }

    public static void clearManualZone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove("manual_prayer_zone")
                .remove("manual_prayer_zone_name")
                .remove("manual_lat")
                .remove("manual_lon")
                .apply();
    }

    public static void setZone(Context context, String zoneCode) {
        if (zoneCode == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("manual_prayer_zone", zoneCode)
                .putString("manual_prayer_zone_name", zoneCode)
                .apply();
        sync(context, null);
    }

    public static void setPrayerZone(Context context, PrayerZone zone) {
        if (zone == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("manual_prayer_zone", zone.code)
                .putString("manual_prayer_zone_name", zone.name)
                .putString("manual_lat", String.valueOf(zone.lat))
                .putString("manual_lon", String.valueOf(zone.lon))
                .apply();
        sync(context, null);
    }

    private static String getActiveKotaId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains("manual_prayer_zone")) {
            return prefs.getString("manual_prayer_zone", DEFAULT_KOTA_ID);
        }
        String loc = AppLocationManager.getLocationName(context).toLowerCase(Locale.ROOT);
        
        if (loc.contains("surabaya")) return "1502";
        if (loc.contains("bandung")) return "1218";
        if (loc.contains("medan")) return "0201";
        if (loc.contains("denpasar") || loc.contains("bali")) return "2201";
        if (loc.contains("yogyakarta")) return "1401";
        if (loc.contains("makassar")) return "2701";
        if (loc.contains("banjarmasin")) return "1901";
        if (loc.contains("palembang")) return "3101";

        return DEFAULT_KOTA_ID; // Default Jakarta
    }

    static class PrayerItem {
        final String name;
        final String timeStr;
        final boolean isNext;
        final boolean isPast;

        PrayerItem(String name, String timeStr, boolean isNext, boolean isPast) {
            this.name = name;
            this.timeStr = timeStr;
            this.isNext = isNext;
            this.isPast = isPast;
        }
    }

    static PrayerItem[] getPrayerItems(Context context) {
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_MONTH);
        long[] times = getTimesForToday(context, day);
        if (times == null || times.length < 6) {
            times = calculateFallback(context, now);
        }
        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue;
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }
        if (nextIdx == -1) nextIdx = 0;

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        PrayerItem[] items = new PrayerItem[6];
        for (int i = 0; i < 6; i++) {
            items[i] = new PrayerItem(
                    PRAYER_NAMES[i],
                    timeFmt.format(new Date(times[i])),
                    i == nextIdx,
                    times[i] < nowMs
            );
        }
        return items;
    }

    static CharSequence getFormatted(Context context) {
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_MONTH);
        long[] times = getTimesForToday(context, day);

        if (times == null || times.length < 6) {
            times = calculateFallback(context, now);
        }

        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;

        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue;
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }
        if (nextIdx == -1) nextIdx = 0;

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SpannableStringBuilder sb = new SpannableStringBuilder();

        String nextName = PRAYER_NAMES[nextIdx].toUpperCase(Locale.ROOT);
        long displayTimeMs = times[nextIdx];
        if (nextIdx == 0 && times[0] < nowMs) {
            Calendar tomorrow = (Calendar) now.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            long[] tomTimes = getTimesForToday(context, tomorrow.get(Calendar.DAY_OF_MONTH));
            if (tomTimes == null || tomTimes.length < 6) tomTimes = calculateFallback(context, tomorrow);
            if (tomTimes != null && tomTimes.length > 0) displayTimeMs = tomTimes[0];
        }
        String nextTime = timeFmt.format(new Date(displayTimeMs));

        int l1Start = sb.length();
        String zoneHeader = getShortZoneName(context).toUpperCase(Locale.ROOT);
        sb.append(zoneHeader).append("  •  SETERUSNYA: ").append(nextName).append(" ").append(nextTime);
        int l1End = sb.length();
        sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), l1Start, l1End, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), l1Start, l1End, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sb.append("\n");

        int[] displayIndices = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < displayIndices.length; i++) {
            int idx = displayIndices[i];
            boolean isNext = (idx == nextIdx);
            int start = sb.length();

            String name = PRAYER_NAMES[idx].toUpperCase(Locale.ROOT);
            String timeStr = timeFmt.format(new Date(times[idx]));

            sb.append(name).append(" ").append(timeStr);
            if (isNext) {
                sb.setSpan(new ForegroundColorSpan(0xFF4ADE80), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new ForegroundColorSpan(0xE6FFFFFF), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (i < displayIndices.length - 1) {
                int dotStart = sb.length();
                sb.append("  •  ");
                sb.setSpan(new ForegroundColorSpan(0x80FFFFFF), dotStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        return sb;
    }

    private static long[] getTimesForToday(Context context, int day) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String jsonStr = prefs.getString(KEY_JSON, null);
            if (jsonStr == null) return null;

            JSONObject obj = new JSONObject(jsonStr);
            JSONArray prayers = obj.getJSONArray("prayers");
            for (int i = 0; i < prayers.length(); i++) {
                JSONObject p = prayers.getJSONObject(i);
                if (p.optInt("day") == day) {
                    long[] res = new long[6];
                    for (int k = 0; k < 6; k++) {
                        res[k] = p.getLong(PRAYER_KEYS[k]) * 1000L;
                    }
                    return res;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long[] calculateFallback(Context context, Calendar cal) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double latitude = prefs.contains("manual_lat")
                ? Double.parseDouble(prefs.getString("manual_lat", String.valueOf(AppLocationManager.getLatitude(context))))
                : AppLocationManager.getLatitude(context);
        double longitude = prefs.contains("manual_lon")
                ? Double.parseDouble(prefs.getString("manual_lon", String.valueOf(AppLocationManager.getLongitude(context))))
                : AppLocationManager.getLongitude(context);

        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        double d = 367.0 * year - Math.floor((7.0 * (year + Math.floor((month + 9.0) / 12.0))) / 4.0)
                + Math.floor((275.0 * month) / 9.0) + day - 730531.5;

        double L = (280.461 + 0.9856474 * d) % 360.0;
        if (L < 0) L += 360.0;
        double M = (357.528 + 0.9856003 * d) % 360.0;
        if (M < 0) M += 360.0;

        double lambda = L + 1.915 * Math.sin(Math.toRadians(M)) + 0.020 * Math.sin(Math.toRadians(2 * M));
        double epsilon = 23.439 - 0.0000004 * d;
        double alpha = Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(epsilon)) * Math.sin(Math.toRadians(lambda)), Math.cos(Math.toRadians(lambda)))) / 15.0;
        double delta = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(epsilon)) * Math.sin(Math.toRadians(lambda))));

        double noon = 12.0 + TIMEZONE - (longitude / 15.0) - (L / 15.0 - alpha);
        if (noon < 0) noon += 24.0;
        if (noon >= 24) noon -= 24.0;

        double fajrHour = noon - sunAngleHour(-20.0, latitude, delta);
        double sunriseHour = noon - sunAngleHour(-0.833, latitude, delta);
        double asrHour = noon + asrHour(1, latitude, delta);
        double sunsetHour = noon + sunAngleHour(-0.833, latitude, delta);
        double ishaHour = noon + sunAngleHour(-18.0, latitude, delta);

        double[] hours = {fajrHour + 0.05, sunriseHour, noon + 0.04, asrHour + 0.04, sunsetHour + 0.05, ishaHour + 0.05};
        long[] res = new long[6];
        Calendar base = (Calendar) cal.clone();
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);

        for (int i = 0; i < 6; i++) {
            int h = (int) hours[i];
            int m = (int) ((hours[i] - h) * 60.0);
            base.set(Calendar.HOUR_OF_DAY, h);
            base.set(Calendar.MINUTE, m);
            res[i] = base.getTimeInMillis();
        }
        return res;
    }

    private static double sunAngleHour(double angle, double lat, double delta) {
        double cosH = (Math.sin(Math.toRadians(angle)) - Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(delta)))
                / (Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(delta)));
        cosH = Math.max(-1.0, Math.min(1.0, cosH));
        return Math.toDegrees(Math.acos(cosH)) / 15.0;
    }

    private static double asrHour(int shadowFactor, double lat, double delta) {
        double phi = Math.abs(lat - delta);
        double cotA = shadowFactor + Math.tan(Math.toRadians(phi));
        double angle = Math.toDegrees(Math.atan(1.0 / cotA));
        return sunAngleHour(angle, lat, delta);
    }

    static class ScheduleItem {
        final String name;
        final String timeFormatted;
        final boolean isNext;
        final boolean isPast;

        ScheduleItem(String name, String timeFormatted, boolean isNext, boolean isPast) {
            this.name = name;
            this.timeFormatted = timeFormatted;
            this.isNext = isNext;
            this.isPast = isPast;
        }
    }

    static java.util.List<ScheduleItem> getFullSchedule(Context context) {
        java.util.List<ScheduleItem> list = new java.util.ArrayList<>();
        Calendar now = Calendar.getInstance();
        long[] times = getTimesForToday(context, now.get(Calendar.DAY_OF_MONTH));
        if (times == null || times.length < 6) times = calculateFallback(context, now);

        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue;
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }
        if (nextIdx == -1) nextIdx = 0;

        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int i = 0; i < times.length; i++) {
            boolean isNext = (i == nextIdx);
            boolean isPast = (times[i] < nowMs);
            list.add(new ScheduleItem(PRAYER_NAMES[i], sdf.format(new Date(times[i])), isNext, isPast));
        }
        return list;
    }

    static String getNextPrayerSummary(Context context) {
        Calendar now = Calendar.getInstance();
        long[] times = getTimesForToday(context, now.get(Calendar.DAY_OF_MONTH));
        if (times == null || times.length < 6) times = calculateFallback(context, now);

        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue;
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }

        long nextTimeMs;
        String prayerName;
        if (nextIdx != -1) {
            prayerName = PRAYER_NAMES[nextIdx];
            nextTimeMs = times[nextIdx];
        } else {
            prayerName = PRAYER_NAMES[0];
            Calendar tomorrow = (Calendar) now.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            long[] tomTimes = getTimesForToday(context, tomorrow.get(Calendar.DAY_OF_MONTH));
            if (tomTimes == null || tomTimes.length < 6) {
                tomTimes = calculateFallback(context, tomorrow);
            }
            nextTimeMs = (tomTimes != null && tomTimes.length > 0) ? tomTimes[0] : (times[0] + 86400000L);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        String zone = getShortZoneName(context);
        return prayerName + " • " + sdf.format(new Date(nextTimeMs)) + " (" + zone + ")";
    }

    static String getHijriDate() {
        try {
            java.time.chrono.HijrahDate h = java.time.chrono.HijrahDate.now();
            int day = h.get(java.time.temporal.ChronoField.DAY_OF_MONTH);
            int month = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR);
            int year = h.get(java.time.temporal.ChronoField.YEAR);
            String[] months = {
                "Muharram", "Safar", "Rabiulawal", "Rabiulakhir",
                "Jamadilawal", "Jamadilakhir", "Rejab", "Syaaban",
                "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah"
            };
            String monthName = (month >= 1 && month <= 12) ? months[month - 1] : "Hijrah";
            return day + " " + monthName + " " + year + "H";
        } catch (Exception e) {
            return "";
        }
    }
}

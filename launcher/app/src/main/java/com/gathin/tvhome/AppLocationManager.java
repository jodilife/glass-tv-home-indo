package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppLocationManager {
    private static final String TAG = "TvHome.Location";
    public static final String PREFS_NAME = "app_location_prefs";

    public static final String KEY_REGION = "region_name";
    public static final String KEY_STATION_ID = "station_id";
    public static final String KEY_LOCATION_NAME = "location_name";
    public static final String KEY_STATION_PLACE = "station_place";
    public static final String KEY_LATITUDE = "latitude";
    public static final String KEY_LONGITUDE = "longitude";

    // Defaults to Indonesia (Jakarta ID01)
    public static final String DEFAULT_REGION = "Indonesia";
    public static final String DEFAULT_STATION_ID = "ID01";
    public static final String DEFAULT_LOCATION_NAME = "Jakarta";
    public static final String DEFAULT_STATION_PLACE = "Monas / Gambir, Jakarta Pusat";
    public static final double DEFAULT_LATITUDE = -6.2088;
    public static final double DEFAULT_LONGITUDE = 106.8456;

    public static class Station {
        public final String id;
        public final String name;
        public final String state;
        public final String country;
        public final String place;
        public final double lat;
        public final double lon;

        public Station(String id, String name, String state, String country, String place, double lat, double lon) {
            this.id = id;
            this.name = name;
            this.state = state != null ? state : "";
            this.country = country != null ? country : "Indonesia";
            this.place = place != null ? place : "";
            this.lat = lat;
            this.lon = lon;
        }

        public String getDisplayName() {
            if (state.isEmpty() || state.equalsIgnoreCase(country)) {
                return name + " (" + country + ")";
            }
            return name + " (" + state + ", " + country + ")";
        }
    }

    private static final Map<String, List<Station>> REGION_STATIONS = new LinkedHashMap<>();
    private static final List<Station> ALL_STATIONS = new ArrayList<>();

    static {
        // --- 🇮🇩 Indonesia (Default - Dipindah ke urutan teratas) ---
        List<Station> idStations = new ArrayList<>();
        idStations.add(new Station("ID01", "Jakarta", "DKI Jakarta", "Indonesia", "Monas / Gambir, Jakarta Pusat", -6.2088, 106.8456));
        idStations.add(new Station("ID02", "Surabaya", "Jawa Timur", "Indonesia", "Pusat Kota Surabaya", -7.2575, 112.7521));
        idStations.add(new Station("ID03", "Bandung", "Jawa Barat", "Indonesia", "Gedung Sate / Alun-alun", -6.9175, 107.6191));
        idStations.add(new Station("ID04", "Medan", "Sumatera Utara", "Indonesia", "Pusat Pasar, Medan", 3.5952, 98.6722));
        idStations.add(new Station("ID05", "Denpasar", "Bali", "Indonesia", "Kuta / Denpasar", -8.6705, 115.2126));
        idStations.add(new Station("ID06", "Batam", "Kepulauan Riau", "Indonesia", "Batam Centre", 1.1301, 104.0529));
        idStations.add(new Station("ID07", "Yogyakarta", "D.I. Yogyakarta", "Indonesia", "Malioboro", -7.7956, 110.3695));
        idStations.add(new Station("ID08", "Makassar", "Sulawesi Selatan", "Indonesia", "Pantai Losari", -5.1477, 119.4327));
        REGION_STATIONS.put("Indonesia", idStations);
        ALL_STATIONS.addAll(idStations);

        // --- 🇲🇾 Malaysia ---
        List<Station> myStations = new ArrayList<>();
        myStations.add(new Station("CA17W", "Putrajaya", "W.P. Putrajaya", "Malaysia", "SK Putrajaya Presint 18 (2)", 2.9158, 101.6909));
        myStations.add(new Station("CA16W", "Cheras", "W.P. Kuala Lumpur", "Malaysia", "SMK Seri Permaisuri", 3.1063, 101.7179));
        myStations.add(new Station("CA15W", "Batu Muda", "W.P. Kuala Lumpur", "Malaysia", "SK Batu Muda", 3.2124, 101.6822));
        myStations.add(new Station("CA19B", "Petaling Jaya", "Selangor", "Malaysia", "Taman Universiti", 3.1090, 101.6548));
        myStations.add(new Station("CA20B", "Shah Alam", "Selangor", "Malaysia", "Seksyen 4, Shah Alam", 3.0683, 101.5058));
        myStations.add(new Station("CA21B", "Klang", "Selangor", "Malaysia", "SMK Raja Lumu", 3.0108, 101.4081));
        myStations.add(new Station("CA22B", "Banting", "Selangor", "Malaysia", "Kolej MARA Banting", 2.8167, 101.6230));
        myStations.add(new Station("CA28M", "Bandaraya Melaka", "Melaka", "Malaysia", "SMK Tinggi Melaka", 2.1909, 102.2571));
        myStations.add(new Station("CA27M", "Bukit Rambai", "Melaka", "Malaysia", "SK Tanjung Minyak 2", 2.2685, 102.1936));
        myStations.add(new Station("CA26M", "Alor Gajah", "Melaka", "Malaysia", "SMK Seri Pengkalan", 2.3709, 102.2232));
        myStations.add(new Station("CA23N", "Seremban", "Negeri Sembilan", "Malaysia", "SK Seremban 2B", 2.6953, 101.9056));
        myStations.add(new Station("CA24N", "Nilai", "Negeri Sembilan", "Malaysia", "SMK Desa Cempaka", 2.8128, 101.7989));
        myStations.add(new Station("CA25N", "Port Dickson", "Negeri Sembilan", "Malaysia", "SK Port Dickson", 2.5283, 101.7967));
        myStations.add(new Station("CA30J", "Larkin (Johor Bahru)", "Johor", "Malaysia", "Pusat Kraftangan Larkin", 1.4947, 103.7419));
        myStations.add(new Station("CA32J", "Pasir Gudang", "Johor", "Malaysia", "SK Pasir Gudang 4", 1.4703, 103.8961));
        myStations.add(new Station("CA31J", "Batu Pahat", "Johor", "Malaysia", "MRSM Batu Pahat", 1.9191, 102.8658));
        myStations.add(new Station("CA33J", "Kluang", "Johor", "Malaysia", "Sekolah Menengah Agama Kluang", 2.0306, 103.3214));
        myStations.add(new Station("CA66J", "Tangkak", "Johor", "Malaysia", "Tapak Semaian Majlis Daerah", 2.2957, 102.5713));
        myStations.add(new Station("CA08P", "Seberang Jaya", "Pulau Pinang", "Malaysia", "SK Seberang Jaya II", 5.3942, 100.3983));
        myStations.add(new Station("CA07P", "Minden", "Pulau Pinang", "Malaysia", "Universiti Sains Malaysia", 5.3581, 100.2975));
        myStations.add(new Station("CA09P", "Balik Pulau", "Pulau Pinang", "Malaysia", "Kolej Vokasional Balik Pulau", 5.3371, 100.2166));
        myStations.add(new Station("CA12A", "Ipoh (Tasek)", "Perak", "Malaysia", "SK Tasek Damai", 4.6397, 101.1083));
        myStations.add(new Station("CA11A", "Taiping", "Perak", "Malaysia", "SK Seri Aman", 4.8569, 100.7417));
        myStations.add(new Station("CA13A", "Seri Manjung", "Perak", "Malaysia", "SK Seri Manjung", 4.1953, 100.6728));
        myStations.add(new Station("CA04K", "Alor Setar", "Kedah", "Malaysia", "Sek. Men. Teknik Alor Setar", 6.1367, 100.3800));
        myStations.add(new Station("CA03K", "Sungai Petani", "Kedah", "Malaysia", "Institut Kemahiran Mara", 5.6706, 100.5217));
        myStations.add(new Station("CA05K", "Langkawi", "Kedah", "Malaysia", "Kompleks Sukan Langkawi", 6.3267, 99.8500));
        myStations.add(new Station("CA01R", "Kangar", "Perlis", "Malaysia", "Institut Latihan Perindustrian", 6.4447, 100.2036));
        myStations.add(new Station("CA38C", "Indera Mahkota (Kuantan)", "Pahang", "Malaysia", "SK Indera Mahkota", 3.8239, 103.2975));
        myStations.add(new Station("CA40C", "Temerloh", "Pahang", "Malaysia", "SMK Seberang Temerloh", 3.4478, 102.4178));
        myStations.add(new Station("CA44T", "Kuala Terengganu", "Terengganu", "Malaysia", "SK Seri Budiman", 5.3117, 103.1325));
        myStations.add(new Station("CA42T", "Kemaman", "Terengganu", "Malaysia", "SMK Bukit Kuang", 4.2622, 103.4258));
        myStations.add(new Station("CA46D", "Kota Bharu", "Kelantan", "Malaysia", "SMK Tanjung Mas", 6.1256, 102.2386));
        myStations.add(new Station("CA48S", "Kota Kinabalu", "Sabah", "Malaysia", "Politeknik Kota Kinabalu", 6.0971, 116.1665));
        myStations.add(new Station("CA49S", "Sandakan", "Sabah", "Malaysia", "SMK Perempuan Sandakan", 5.8456, 118.0678));
        myStations.add(new Station("CA50S", "Tawau", "Sabah", "Malaysia", "SMK Tawau", 4.2564, 117.8931));
        myStations.add(new Station("CA54Q", "Kuching", "Sarawak", "Malaysia", "Cawangan Farmasi Logistik", 1.5622, 110.3889));
        myStations.add(new Station("CA55Q", "Miri", "Sarawak", "Malaysia", "ILP Miri", 4.4947, 114.0434));
        myStations.add(new Station("CA58Q", "Bintulu", "Sarawak", "Malaysia", "Balai Polis Bintulu", 3.1772, 113.0412));
        myStations.add(new Station("CA59Q", "Sibu", "Sarawak", "Malaysia", "SK Bandaran Sibu No. 4", 2.2981, 111.8344));
        myStations.add(new Station("CA53L", "Labuan", "W.P. Labuan", "Malaysia", "Kolej Matrikulasi Labuan", 5.3347, 115.2289));
        REGION_STATIONS.put("Malaysia", myStations);
        ALL_STATIONS.addAll(myStations);

        // --- 🇸🇬 Singapore ---
        List<Station> sgStations = new ArrayList<>();
        sgStations.add(new Station("SG01", "Marina Bay & Central", "Central", "Singapore", "Marina Bay / Downtown Core", 1.3521, 103.8198));
        sgStations.add(new Station("SG02", "Jurong East & West", "West", "Singapore", "Jurong Gateway Hub", 1.3329, 103.7436));
        sgStations.add(new Station("SG03", "Tampines & Changi", "East", "Singapore", "Tampines Central", 1.3532, 103.9452));
        sgStations.add(new Station("SG04", "Woodlands", "North", "Singapore", "Woodlands Civic Centre", 1.4382, 103.7890));
        REGION_STATIONS.put("Singapore", sgStations);
        ALL_STATIONS.addAll(sgStations);

        // --- 🇧🇳 Brunei Darussalam ---
        List<Station> bnStations = new ArrayList<>();
        bnStations.add(new Station("BN01", "Bandar Seri Begawan", "Brunei-Muara", "Brunei", "Pusat Bandar BSB", 4.9031, 114.9398));
        bnStations.add(new Station("BN02", "Kuala Belait", "Belait", "Brunei", "Pekan Kuala Belait", 4.5836, 114.2312));
        bnStations.add(new Station("BN03", "Tutong", "Tutong", "Brunei", "Pekan Tutong", 4.8028, 114.6492));
        REGION_STATIONS.put("Brunei", bnStations);
        ALL_STATIONS.addAll(bnStations);

        // --- 🇹🇭 Thailand ---
        List<Station> thStations = new ArrayList<>();
        thStations.add(new Station("TH01", "Bangkok", "Central", "Thailand", "Siam / Sukhumvit, Bangkok", 13.7563, 100.5018));
        thStations.add(new Station("TH02", "Phuket", "South", "Thailand", "Patong / Phuket Town", 7.8804, 98.3923));
        thStations.add(new Station("TH03", "Chiang Mai", "North", "Thailand", "Old City, Chiang Mai", 18.7883, 98.9853));
        thStations.add(new Station("TH04", "Hat Yai", "Songkhla", "Thailand", "Hat Yai City Hub", 7.0084, 100.4767));
        thStations.add(new Station("TH05", "Pattaya", "Chonburi", "Thailand", "Pattaya Beach", 12.9276, 100.8771));
        REGION_STATIONS.put("Thailand", thStations);
        ALL_STATIONS.addAll(thStations);

        // --- 🇯🇵 Jepun (Japan) ---
        List<Station> jpStations = new ArrayList<>();
        jpStations.add(new Station("JP01", "Tokyo", "Kanto", "Jepun", "Shinjuku / Shibuya, Tokyo", 35.6762, 139.6503));
        jpStations.add(new Station("JP02", "Osaka", "Kansai", "Jepun", "Umeda / Namba, Osaka", 34.6937, 135.5023));
        jpStations.add(new Station("JP03", "Kyoto", "Kansai", "Jepun", "Gion / Kyoto Station", 35.0116, 135.7681));
        jpStations.add(new Station("JP04", "Sapporo", "Hokkaido", "Jepun", "Odori Park, Sapporo", 43.0618, 141.3545));
        jpStations.add(new Station("JP05", "Fukuoka", "Kyushu", "Jepun", "Hakata, Fukuoka", 33.5904, 130.4017));
        REGION_STATIONS.put("Jepun (Japan)", jpStations);
        ALL_STATIONS.addAll(jpStations);

        // --- 🇰🇷 Korea Selatan (South Korea) ---
        List<Station> krStations = new ArrayList<>();
        krStations.add(new Station("KR01", "Seoul", "Capital Region", "Korea Selatan", "Myeongdong / Gangnam, Seoul", 37.5665, 126.9780));
        krStations.add(new Station("KR02", "Busan", "Gyeongsang", "Korea Selatan", "Haeundae / Seomyeon, Busan", 35.1796, 129.0756));
        krStations.add(new Station("KR03", "Incheon", "Capital Region", "Korea Selatan", "Songdo International City", 37.4563, 126.7052));
        krStations.add(new Station("KR04", "Jeju", "Jeju Island", "Korea Selatan", "Jeju City", 33.4996, 126.5312));
        REGION_STATIONS.put("Korea Selatan", krStations);
        ALL_STATIONS.addAll(krStations);

        // --- 🇻🇳 Vietnam ---
        List<Station> vnStations = new ArrayList<>();
        vnStations.add(new Station("VN01", "Ho Chi Minh City", "South", "Vietnam", "District 1, Saigon", 10.8231, 106.6297));
        vnStations.add(new Station("VN02", "Hanoi", "North", "Vietnam", "Hoan Kiem, Hanoi", 21.0285, 105.8542));
        vnStations.add(new Station("VN03", "Da Nang", "Central", "Vietnam", "My Khe / Hai Chau", 16.0544, 108.2022));
        REGION_STATIONS.put("Vietnam", vnStations);
        ALL_STATIONS.addAll(vnStations);

        // --- 🇵🇭 Filipina (Philippines) ---
        List<Station> phStations = new ArrayList<>();
        phStations.add(new Station("PH01", "Manila", "Metro Manila", "Filipina", "Makati / BGC, Manila", 14.5995, 120.9842));
        phStations.add(new Station("PH02", "Cebu City", "Visayas", "Filipina", "Cebu Business Park", 10.3157, 123.8854));
        phStations.add(new Station("PH03", "Davao City", "Mindanao", "Filipina", "Poblacion, Davao", 7.1907, 125.4578));
        REGION_STATIONS.put("Filipina", phStations);
        ALL_STATIONS.addAll(phStations);

        // --- 🇸🇦 Timur Tengah & Arab (West Asia) ---
        List<Station> waStations = new ArrayList<>();
        waStations.add(new Station("WA01", "Makkah (Mecca)", "Makkah", "Arab Saudi", "Masjidil Haram", 21.4225, 39.8262));
        waStations.add(new Station("WA02", "Madinah (Medina)", "Madinah", "Arab Saudi", "Masjid an-Nabawi", 24.5247, 39.5692));
        waStations.add(new Station("WA03", "Dubai", "Dubai", "UAE", "Downtown Dubai / Burj Khalifa", 25.2048, 55.2708));
        waStations.add(new Station("WA04", "Doha", "Ad-Dawhah", "Qatar", "Corniche, Doha", 25.2854, 51.5310));
        REGION_STATIONS.put("Arab Saudi & UAE", waStations);
        ALL_STATIONS.addAll(waStations);
    }

    public static List<String> getAvailableRegions() {
        return new ArrayList<>(REGION_STATIONS.keySet());
    }

    public static List<Station> getStationsForRegion(String region) {
        if (region == null || !REGION_STATIONS.containsKey(region)) {
            return REGION_STATIONS.get("Indonesia");
        }
        return Collections.unmodifiableList(REGION_STATIONS.get(region));
    }

    public static List<Station> getPresetStations() {
        return Collections.unmodifiableList(ALL_STATIONS);
    }

    public static String getActiveRegion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_REGION, DEFAULT_REGION);
    }

    public static void setActiveRegion(Context context, String region) {
        if (region == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_REGION, region).apply();
    }

    public static String getStationId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_STATION_ID, DEFAULT_STATION_ID);
    }

    public static String getLocationName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LOCATION_NAME, DEFAULT_LOCATION_NAME);
    }

    public static String getStationPlace(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_STATION_PLACE, DEFAULT_STATION_PLACE);
    }

    public static double getLatitude(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            return Double.parseDouble(prefs.getString(KEY_LATITUDE, String.valueOf(DEFAULT_LATITUDE)));
        } catch (Exception ignored) {
            return DEFAULT_LATITUDE;
        }
    }

    public static double getLongitude(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            return Double.parseDouble(prefs.getString(KEY_LONGITUDE, String.valueOf(DEFAULT_LONGITUDE)));
        } catch (Exception ignored) {
            return DEFAULT_LONGITUDE;
        }
    }

    public static void setStation(Context context, Station s) {
        if (s == null) return;
        setCustomLocation(context, s.id, s.name, s.country, s.place, s.lat, s.lon);
    }

    public static void setCustomLocation(Context context, String stationId, String locationName, String place, double lat, double lon) {
        setCustomLocation(context, stationId, locationName, DEFAULT_REGION, place, lat, lon);
    }

    public static void setCustomLocation(Context context, String stationId, String locationName, String country, String place, double lat, double lon) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_STATION_ID, stationId)
                .putString(KEY_LOCATION_NAME, locationName)
                .putString(KEY_REGION, country != null ? country : DEFAULT_REGION)
                .putString(KEY_STATION_PLACE, place)
                .putString(KEY_LATITUDE, String.valueOf(lat))
                .putString(KEY_LONGITUDE, String.valueOf(lon))
                .apply();

        Log.i(TAG, "Location set to: " + locationName + " (" + country + ") [" + lat + ", " + lon + "]");

        WeatherAirQuality.sync(context, null);
        PrayerTimes.sync(context, null);
    }

    public static Station findStationByQuery(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Station s : ALL_STATIONS) {
            if (s.id.equalsIgnoreCase(q) || s.name.toLowerCase(Locale.ROOT).contains(q) || s.state.toLowerCase(Locale.ROOT).contains(q) || s.country.toLowerCase(Locale.ROOT).contains(q)) {
                return s;
            }
        }
        return null;
    }

    public static Station findNearestStation(double lat, double lon) {
        Station best = null;
        double minDistance = Double.MAX_VALUE;
        for (Station s : ALL_STATIONS) {
            double dLat = s.lat - lat;
            double dLon = s.lon - lon;
            double dist = dLat * dLat + dLon * dLon;
            if (dist < minDistance) {
                minDistance = dist;
                best = s;
            }
        }
        return best != null ? best : ALL_STATIONS.get(0);
    }
}

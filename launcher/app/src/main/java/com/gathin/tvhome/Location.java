package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengaturan Lokasi TV: Memilih wilayah/kota di Indonesia untuk jadwal shalat dan 
 * koordinat cuaca (Open-Meteo). Pilihan yang dibuat di TV akan menyimpan preferensi 
 * zona waktu shalat dan lokasi cuaca.
 */
final class Location {
    static final class Zone {
        final String code, state, town, districts;
        final double lat, lon;

        Zone(String[] r) {
            code = r[0];
            state = r[1];
            town = r[2];
            districts = r[3];
            lat = Double.parseDouble(r[4]);
            lon = Double.parseDouble(r[5]);
        }
    }

    /** Kode, Provinsi, Kota/Kabupaten Utama, Cakupan Wilayah, Lintang, Bujur. */
    private static final String[][] ZONES = {
        // DKI Jakarta
        {"JKT01", "DKI Jakarta", "Jakarta Pusat", "Gambir, Tanah Abang, Menteng, Senen, Cempaka Putih, Johar Baru, Kemayoran, Sawah Besar", "-6.1865", "106.8340"},
        {"JKT02", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru, Kebayoran Lama, Pesanggrahan, Cilandak, Pasar Minggu, Jagakarsa, Mampang Prapatan, Pancoran, Tebet, Setiabudi", "-6.2615", "106.8106"},
        {"JKT03", "DKI Jakarta", "Jakarta Barat", "Cengkareng, Grogol Petamburan, Kalideres, Kebon Jeruk, Kembangan, Palmerah, Tamansari, Tambora", "-6.1683", "106.7589"},
        {"JKT04", "DKI Jakarta", "Jakarta Timur", "Matraman, Pulo Gadung, Jatinegara, Duren Sawit, Kramat Jati, Makasar, Pasar Rebo, Ciracas, Cipayung, Cakung", "-6.2250", "106.9004"},
        {"JKT05", "DKI Jakarta", "Jakarta Utara", "Penjaringan, Pademangan, Tanjung Priok, Koja, Kelapa Gading, Cilincing", "-6.1214", "106.8906"},

        // Jawa Barat
        {"JBR01", "Jawa Barat", "Bandung", "Kota Bandung, Cimahi, Kabupaten Bandung, Bandung Barat", "-6.9175", "107.6191"},
        {"JBR02", "Jawa Barat", "Bogor", "Kota Bogor, Kabupaten Bogor, Depok", "-6.5971", "106.7997"},
        {"JBR03", "Jawa Barat", "Bekasi", "Kota Bekasi, Kabupaten Bekasi", "-6.2383", "106.9756"},
        {"JBR04", "Jawa Barat", "Depok", "Kota Depok", "-6.4025", "106.8195"},
        {"JBR05", "Jawa Barat", "Cirebon", "Kota Cirebon, Kabupaten Cirebon, Indramayu", "-6.7320", "108.5523"},

        // Jawa Tengah & DIY
        {"JTG01", "Jawa Tengah", "Semarang", "Kota Semarang, Kabupaten Semarang, Kendal, Demak", "-6.9667", "110.4167"},
        {"JTG02", "Jawa Tengah", "Surakarta", "Kota Surakarta (Solo), Sukoharjo, Karanganyar, Sragen, Boyolali, Klaten, Wonogiri", "-7.5561", "110.8316"},
        {"DIY01", "DI Yogyakarta", "Yogyakarta", "Kota Yogyakarta, Sleman, Bantul, Kulon Progo, Gunungkidul", "-7.7956", "110.3695"},

        // Jawa Timur
        {"JTM01", "Jawa Timur", "Surabaya", "Kota Surabaya, Sidoarjo, Gresik", "-7.2575", "112.7521"},
        {"JTM02", "Jawa Timur", "Malang", "Kota Malang, Kabupaten Malang, Kota Batu", "-7.9666", "112.6326"},

        // Sumatera
        {"SMT01", "Sumatera Utara", "Medan", "Kota Medan, Deli Serdang, Binjai", "3.5952", "98.6722"},
        {"SMT02", "Sumatera Selatan", "Palembang", "Kota Palembang, Banyuasin, Ogan Ilir", "-2.9761", "104.7754"},

        // Sulawesi & Lainnya
        {"SLW01", "Sulawesi Selatan", "Makassar", "Kota Makassar, Gowa, Maros", "-5.1477", "119.4327"}
    };

    private static List<Zone> all;

    private Location() {
    }

    static synchronized List<Zone> all() {
        if (all == null) {
            all = new ArrayList<>();
            for (String[] r : ZONES) all.add(new Zone(r));
        }
        return all;
    }

    /** Daftar provinsi sesuai urutan list. */
    static List<String> states() {
        List<String> s = new ArrayList<>();
        for (Zone z : all()) if (!s.contains(z.state)) s.add(z.state);
        return s;
    }

    static List<Zone> inState(String state) {
        List<Zone> s = new ArrayList<>();
        for (Zone z : all()) if (z.state.equals(state)) s.add(z);
        return s;
    }

    static Zone byCode(String code) {
        if (code == null) return null;
        for (Zone z : all()) if (z.code.equalsIgnoreCase(code)) return z;
        return null;
    }

    /** Zona yang dipilih di TV, atau null jika belum ada. */
    static Zone chosen(Context ctx) {
        return byCode(prefs(ctx).getString("zone", null));
    }

    static void choose(Context ctx, Zone z) {
        prefs(ctx).edit().putString("zone", z.code).apply();
    }

    /** Apakah layar pemilihan lokasi sudah pernah muncul otomatis saat awal (first start). */
    static boolean asked(Context ctx) {
        return prefs(ctx).getBoolean("asked", false);
    }

    static void setAsked(Context ctx) {
        prefs(ctx).edit().putBoolean("asked", true).apply();
    }

    /** Menyimpan data aktif yang digunakan Home dan dibaca oleh aplikasi Cuaca (LocationProvider). */
    static void publish(Context ctx, String code, String town, String state, double lat, double lon) {
        prefs(ctx).edit().putString("now_code", code).putString("now_town", town).putString("now_state", state)
                .putString("now_lat", String.valueOf(lat)).putString("now_lon", String.valueOf(lon)).apply();
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("location", Context.MODE_PRIVATE);
    }
}

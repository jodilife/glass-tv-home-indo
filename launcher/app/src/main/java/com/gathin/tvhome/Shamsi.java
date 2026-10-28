package com.gathin.tvhome;

import java.util.Calendar;
import java.util.Locale;

/** Helper untuk menampilkan tanggal Masehi dalam format Bahasa Indonesia. */
final class Shamsi {

    private static final String[] MONTHS = {
            "Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember"
    };

    private Shamsi() {
    }

    /** e.g. "28 Oktober 2026" */
    static String today() {
        Calendar c = Calendar.getInstance();
        int day = c.get(Calendar.DAY_OF_MONTH);
        int month = c.get(Calendar.MONTH); // 0-11
        int year = c.get(Calendar.YEAR);
        
        return day + " " + MONTHS[month] + " " + year;
    }
}

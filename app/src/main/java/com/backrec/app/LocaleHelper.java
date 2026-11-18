package com.backrec.app;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Язык приложения, выбранный пользователем ("" = как в системе). */
public final class LocaleHelper {
    private LocaleHelper() { }

    public static final String[] CODES = {"", "en", "de", "es", "fr", "it", "pl", "pt", "tr", "uk", "ru"};
    public static final String[] NAMES = {null, "English", "Deutsch", "Español", "Français", "Italiano",
            "Polski", "Português", "Türkçe", "Українська", "Русский"};

    public static String get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "");
    }

    public static void set(Context c, String code) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", code).commit();
    }

    public static Context wrap(Context base) {
        String code = get(base);
        if (code.isEmpty()) return base;
        Locale loc = new Locale(code);
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        return base.createConfigurationContext(cfg);
    }

    /** Короткая метка для кнопки: RU / UK / EN, пусто для системного. */
    public static String shortLabel(Context c) {
        return get(c).toUpperCase(Locale.ROOT);
    }
}

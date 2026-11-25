package in.lalitenterprises.sales;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;

/** Fake-GPS detection (v1.1). Best effort: a rooted / modified phone can hide all of these signals. */
final class MockCheck {
    private MockCheck() {}

    /** Known fake-GPS apps (also listed in AndroidManifest <queries>, so they are visible without QUERY_ALL_PACKAGES). */
    static final String[] KNOWN = {
        "com.lexa.fakegps", "com.incorporateapps.fakegps.fre", "com.blogspot.newapphorizons.fakegps", "com.theappninjas.fakegpsjoystick",
        "com.theappninjas.gpsjoystick", "com.rosteam.gpsemulator", "ru.gavrikov.mocklocations", "com.evezzon.fakegps", "com.gsmartstudio.fakegps",
        "com.fakegps.mock", "com.just4funtools.fakegpslocationprofessional", "com.divi.fakeGPS", "com.hopefactory2021.fakegpslocation",
        "com.location.changer.fake.gps.spoof.emulator", "com.lkr.fakelocation", "com.marlon.floating.fakelocation", "fake.gps.location",
        "com.mock.location", "com.fakelocation.gpschanger", "com.tim.apps.mockgps", "com.kkinfosis.fakegps", "com.applabstudios.fakegps",
        "com.gps.faker", "com.dreams.fakegps", "com.xdoapp.virtualphonenavigation"
    };

    /** True when Android marks this fix as coming from a mock (test) provider. */
    @SuppressWarnings("deprecation")
    static boolean isMock(Location l) {
        if (l == null) return false;
        try { return Build.VERSION.SDK_INT >= 31 ? l.isMock() : l.isFromMockProvider(); } catch (Throwable t) { return false; }
    }

    /** Known fake-GPS apps that are installed. */
    static List<String> installed(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String p : KNOWN) {
            try { pm.getApplicationInfo(p, 0); out.add(p); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Installed apps that are currently selected as "mock location app" in Developer options (AppOps allowed). */
    @SuppressWarnings("deprecation")
    static List<String> active(Context c, List<String> installed) {
        List<String> out = new ArrayList<>();
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            PackageManager pm = c.getPackageManager();
            for (String p : installed) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    int mode = Build.VERSION.SDK_INT >= 29 ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, ai.uid, p)
                                                           : ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, ai.uid, p);
                    if (mode == AppOpsManager.MODE_ALLOWED) out.add(p);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Old Android (< 6) global "Allow mock locations" switch. Always "0" on newer versions. */
    @SuppressWarnings("deprecation")
    static boolean legacySetting(Context c) {
        try { return "1".equals(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION)); } catch (Exception e) { return false; }
    }

    static String label(Context c, String pkg) {
        try { PackageManager pm = c.getPackageManager(); return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception e) { return pkg; }
    }
}

package in.lalitenterprises.sales;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Small native helper for LE Sales: permission status, battery-optimisation exemption,
 * OEM autostart screens, battery level. Location tracking itself is done by
 * @capacitor-community/background-geolocation (foreground service).
 */
@CapacitorPlugin(name = "LENative")
public class LENativePlugin extends Plugin {

    private boolean granted(String p) {
        return ContextCompat.checkSelfPermission(getContext(), p) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void status(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("fine", granted(Manifest.permission.ACCESS_FINE_LOCATION));
        r.put("coarse", granted(Manifest.permission.ACCESS_COARSE_LOCATION));
        r.put("background", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        r.put("notifications", NotificationManagerCompat.from(c).areNotificationsEnabled());
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        r.put("batteryUnrestricted", Build.VERSION.SDK_INT < 23 || (pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName())));
        android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        boolean gps = false;
        try { gps = lm != null && (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)); } catch (Exception ignored) {}
        r.put("locationOn", gps);
        r.put("manufacturer", Build.MANUFACTURER);
        r.put("model", Build.MODEL);
        r.put("sdk", Build.VERSION.SDK_INT);
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            r.put("appVersion", pi.versionName);
        } catch (Exception e) { r.put("appVersion", "?"); }
        call.resolve(r);
    }

    @PluginMethod
    public void battery(PluginCall call) {
        Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSObject r = new JSObject();
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            r.put("level", level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1);
            r.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
        } else { r.put("level", -1); r.put("charging", false); }
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7101);
        }
        call.resolve();
    }

    @PluginMethod
    public void requestForegroundLocation(PluginCall call) {
        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7102);
        call.resolve();
    }

    /** Android 10: system dialog with "Allow all the time". Android 11+: opens the app's location permission page. */
    @PluginMethod
    public void requestBackgroundLocation(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 7103);
        }
        call.resolve();
    }

    @SuppressLint("BatteryLife")
    @PluginMethod
    public void requestBatteryUnrestricted(PluginCall call) {
        Context c = getContext();
        try {
            Intent i = BuildConfig.DIRECT_BATTERY_PROMPT
                ? new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()))
                : new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            openDetails(c);
        }
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) { openDetails(getContext()); call.resolve(); }

    @PluginMethod
    public void openLocationSettings(PluginCall call) {
        try { Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); }
        catch (Exception e) { openDetails(getContext()); }
        call.resolve();
    }

    /** Xiaomi / Vivo / Oppo / Realme / OnePlus / Huawei / Samsung "autostart" or app-launch screens; falls back to app details. */
    @PluginMethod
    public void openAutostart(PluginCall call) {
        Context c = getContext();
        String[][] targets = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        JSObject r = new JSObject();
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    c.startActivity(i);
                    r.put("opened", t[0]);
                    call.resolve(r);
                    return;
                }
            } catch (Exception ignored) {}
        }
        openDetails(c);
        r.put("opened", "app-details");
        call.resolve(r);
    }

    /* ---------- all-day tracking (TrackingService) ---------- */

    @PluginMethod
    public void startTracking(PluginCall call) {
        String uid = call.getString("uid"), rt = call.getString("refreshToken"), key = call.getString("apiKey");
        if (uid == null || rt == null || key == null) { call.reject("uid, refreshToken and apiKey are required"); return; }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            call.reject("Location permission not granted", "NOT_AUTHORIZED"); return;
        }
        Context c = getContext();
        android.content.SharedPreferences p = TrackingService.prefs(c);
        boolean sameDay = TrackingService.today().equals(p.getString("day", "")) && uid.equals(p.getString("uid", ""));
        android.content.SharedPreferences.Editor e = p.edit()
            .putBoolean("active", true)
            .putString("uid", uid)
            .putString("name", call.getString("name", ""))
            .putString("refreshToken", rt)
            .putString("apiKey", key)
            .putString("projectId", call.getString("projectId", "le-sales-app"))
            .putInt("stopHour", call.getInt("stopHour", 21))
            .putString("day", TrackingService.today())
            .putString("emuHost", call.getString("emuHost", ""))
            .putString("lastError", "");
        if (!sameDay) e.putInt("sentCount", 0).putLong("startedAt", System.currentTimeMillis()).putString("queue", "[]");
        e.apply();
        if (TrackingService.shouldAutoStop(p)) { p.edit().putBoolean("active", false).apply(); call.reject("It is after the auto-stop time", "AFTER_HOURS"); return; }
        try {
            ContextCompat.startForegroundService(c, new Intent(c, TrackingService.class));
        } catch (Exception ex) {
            p.edit().putBoolean("active", false).apply();
            call.reject("Could not start location sharing: " + ex.getMessage()); return;
        }
        call.resolve(trackingJson(c));
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        TrackingService.endDay(getContext(), call.getString("reason", "end-day"));
        if (call.getBoolean("forget", false)) TrackingService.prefs(getContext()).edit().remove("refreshToken").remove("queue").apply();
        call.resolve(trackingJson(getContext()));
    }

    @PluginMethod
    public void trackingStatus(PluginCall call) { call.resolve(trackingJson(getContext())); }

    /** Customer check-in fix from the app; sent with the next batch (only while the day is on). */
    @PluginMethod
    public void checkin(PluginCall call) {
        android.content.SharedPreferences p = TrackingService.prefs(getContext());
        if (p.getBoolean("active", false) && call.getDouble("lat") != null && call.getDouble("lng") != null) {
            Long ts = call.getLong("ts");
            TrackingService.enqueueExternal(getContext(), call.getDouble("lat"), call.getDouble("lng"),
                call.getDouble("accuracy", 0d), ts != null ? ts : System.currentTimeMillis(), "checkin");
        }
        call.resolve();
    }

    private static JSObject trackingJson(Context c) {
        android.content.SharedPreferences p = TrackingService.prefs(c);
        JSObject r = new JSObject();
        boolean active = p.getBoolean("active", false) && !TrackingService.shouldAutoStop(p);
        r.put("active", active);
        r.put("running", p.getBoolean("running", false));
        r.put("uid", p.getString("uid", null));
        r.put("day", p.getString("day", null));
        r.put("startedAt", p.getLong("startedAt", 0));
        r.put("lastFixAt", p.getLong("lastFixAt", 0));
        r.put("lastSentAt", p.getLong("lastSentAt", 0));
        r.put("sentCount", p.getInt("sentCount", 0));
        r.put("queued", TrackingService.rest(p));
        r.put("lastError", p.getString("lastError", ""));
        r.put("endReason", p.getString("endReason", ""));
        r.put("endedAt", p.getLong("endedAt", 0));
        r.put("stopHour", p.getInt("stopHour", 21));
        return r;
    }

    private static void openDetails(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}

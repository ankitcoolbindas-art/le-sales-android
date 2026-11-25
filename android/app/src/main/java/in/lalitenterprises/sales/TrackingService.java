package in.lalitenterprises.sales;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All-day location sharing for LE Sales (Android).
 * Foreground service (type=location) with a persistent notification. Gets a fix about every minute,
 * sends one to Firestore every ~3 min, or sooner when the salesperson has moved >=100 m.
 * Writes natively over HTTPS (Firestore REST + the signed-in user's Firebase token), so it keeps
 * working when the app is in the background, the screen is locked, or the app is swiped away.
 * Stops on End day, sign-out, at the configured hour (default 21:00), or at midnight.
 */
public class TrackingService extends Service {
    static final String TAG = "LETracking";
    static final String PREFS = "le_tracking";
    static final String CHANNEL = "le_location_sharing";
    static final int NOTIF_ID = 4242;
    static final String ACTION_STOP = "in.lalitenterprises.sales.STOP_TRACKING";

    static final long FIX_INTERVAL_MS = 60_000;
    static final long FIX_MIN_INTERVAL_MS = 30_000;
    static final long SEND_EVERY_MS = 180_000;   // heartbeat while standing still
    static final long MOVE_MIN_GAP_MS = 60_000;
    static final float MOVE_M = 100f;
    static final int QUEUE_MAX = 1500;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Location lastSent;
    private long lastSentAt;
    private String idToken;
    private long idTokenExp;

    /** Debug builds only: point at the local Firebase emulators (auth :9099, firestore :8080) for testing. */
    static String fsBase(SharedPreferences p) {
        String e = BuildConfig.DEBUG ? p.getString("emuHost", "") : "";
        return e.isEmpty() ? "https://firestore.googleapis.com/v1/" : "http://" + e + ":8080/v1/";
    }
    static String tokenUrl(SharedPreferences p) {
        String e = BuildConfig.DEBUG ? p.getString("emuHost", "") : "";
        return (e.isEmpty() ? "https://" : "http://" + e + ":9099/") + "securetoken.googleapis.com/v1/token?key=";
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = prefs(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { endDay(this, "stopped"); return START_NOT_STICKY; }
        // Restarted by the system (intent == null) or by the app: continue only if the day is still on.
        if (!p.getBoolean("active", false) || p.getString("uid", null) == null) { stopSelf(); return START_NOT_STICKY; }
        if (shouldAutoStop(p)) { endDay(this, "auto-stop"); return START_NOT_STICKY; }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(null),
                Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
            p.edit().putString("lastError", "Could not start: " + e.getClass().getSimpleName() + " — open the app").apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        p.edit().putBoolean("running", true).apply();
        startUpdates();
        return START_STICKY;
    }

    private void startUpdates() {
        if (callback != null) return;
        fused = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FIX_MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build();
        callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                Location l = r.getLastLocation();
                if (l != null) onFix(l);
            }
        };
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
            prefs(this).edit().putString("lastError", "").apply();
        } catch (SecurityException e) {
            prefs(this).edit().putString("lastError", "Location permission missing").apply();
            endDay(this, "no-permission");
        }
    }

    private void onFix(Location l) {
        SharedPreferences p = prefs(this);
        if (shouldAutoStop(p)) { endDay(this, "auto-stop"); return; }
        p.edit().putLong("lastFixAt", System.currentTimeMillis()).putFloat("lastAcc", l.getAccuracy()).apply();
        long now = System.currentTimeMillis();
        String reason = null;
        if (lastSent == null) reason = "start";
        else if (now - lastSentAt >= SEND_EVERY_MS) reason = "interval";
        else if (now - lastSentAt >= MOVE_MIN_GAP_MS && l.distanceTo(lastSent) >= MOVE_M && l.getAccuracy() < 200) reason = "move";
        if (reason == null) return;
        lastSent = l; lastSentAt = now;
        enqueue(toDoc(l, reason, p));
        flushAsync();
    }

    /** Queue a check-in fix coming from the app (customer visit). */
    static void enqueueExternal(Context c, double lat, double lng, double acc, long ts, String reason) {
        SharedPreferences p = prefs(c);
        try {
            JSONObject d = baseDoc(c, p, lat, lng, acc, ts, reason);
            synchronized (TrackingService.class) { appendQueue(p, d); }
        } catch (Exception ignored) {}
    }

    private JSONObject toDoc(Location l, String reason, SharedPreferences p) {
        try {
            JSONObject d = baseDoc(this, p, l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getTime() > 0 ? l.getTime() : System.currentTimeMillis(), reason);
            if (l.hasSpeed()) d.put("speed", (double) l.getSpeed());
            if (l.hasBearing()) d.put("heading", (double) l.getBearing());
            return d;
        } catch (Exception e) { return null; }
    }

    static JSONObject baseDoc(Context c, SharedPreferences p, double lat, double lng, double acc, long ts, String reason) throws Exception {
        JSONObject d = new JSONObject();
        d.put("ownerUid", p.getString("uid", ""));
        d.put("ownerName", p.getString("name", ""));
        d.put("lat", lat); d.put("lng", lng);
        d.put("accuracy", Math.round(acc));
        d.put("ts", ts);
        d.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts)));
        d.put("source", "android");
        d.put("reason", reason);
        d.put("deviceId", (Build.MANUFACTURER + " " + Build.MODEL).trim());
        d.put("appVersion", "android-" + BuildConfig.VERSION_NAME);
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level >= 0 && scale > 0) d.put("battery", Math.round(level * 100.0 / scale) / 100.0);
            d.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
        }
        return d;
    }

    private void enqueue(JSONObject d) {
        if (d == null) return;
        synchronized (TrackingService.class) { appendQueue(prefs(this), d); }
    }

    static void appendQueue(SharedPreferences p, JSONObject d) {
        try {
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            q.put(d);
            while (q.length() > QUEUE_MAX) q.remove(0);
            p.edit().putString("queue", q.toString()).apply();
        } catch (Exception ignored) {}
    }

    void flushAsync() { net.execute(this::flush); }

    private void flush() {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LESales:send");
            wl.acquire(45_000);
        } catch (Exception ignored) {}
        SharedPreferences p = prefs(this);
        try {
            JSONArray q;
            synchronized (TrackingService.class) { q = new JSONArray(p.getString("queue", "[]")); }
            if (q.length() == 0) return;
            String token = token(p);
            if (token == null) return;
            int n = Math.min(q.length(), 100);
            String project = p.getString("projectId", "le-sales-app");
            String base = "projects/" + project + "/databases/(default)/documents/";
            String url = fsBase(p) + base.substring(0, base.length() - 1) + ":commit";
            JSONArray locWrites = new JSONArray();
            JSONObject latest = null;
            for (int i = 0; i < n; i++) {
                JSONObject d = q.getJSONObject(i);
                String id = d.getString("ownerUid") + "_" + Long.toString(d.getLong("ts"), 36) + "a";
                d.put("updatedAt", System.currentTimeMillis());
                locWrites.put(new JSONObject().put("update", new JSONObject().put("name", base + "locations/" + id).put("fields", fields(d))));
                if (latest == null || d.getLong("ts") >= latest.getLong("ts")) latest = d;
            }
            JSONObject lastWrite = new JSONObject().put("update", new JSONObject()
                .put("name", base + "lastLocation/" + latest.getString("ownerUid")).put("fields", fields(latest)));
            JSONArray all = new JSONArray();
            for (int i = 0; i < locWrites.length(); i++) all.put(locWrites.get(i));
            all.put(lastWrite);
            // Firestore REST "commit" is atomic and checked by firestore.rules with the user's token.
            int code = commit(url, all, token);
            if (code == 401) { idToken = null; p.edit().putString("lastError", "Sign-in expired — retrying").apply(); return; }
            int ok = 0;
            boolean lastDenied = false;
            if (code == 200) ok = n;
            else if (code == 403 || code == 400 || code == 409) {
                // one bad item (e.g. a duplicate that was already saved) fails the whole batch: send one by one
                for (int i = 0; i < locWrites.length(); i++) {
                    int c = commit(url, new JSONArray().put(locWrites.get(i)), token);
                    if (c == 200) ok++; else if (c >= 500 || c < 0) throw new java.io.IOException("server " + c);
                }
                int c = commit(url, new JSONArray().put(lastWrite), token);
                lastDenied = c == 403;
            } else { p.edit().putString("lastError", "Server " + code + " — will retry").apply(); return; }
            synchronized (TrackingService.class) {
                JSONArray cur = new JSONArray(p.getString("queue", "[]")), rest = new JSONArray();
                for (int i = n; i < cur.length(); i++) rest.put(cur.get(i));
                p.edit().putString("queue", rest.toString()).apply();
            }
            p.edit().putLong("lastSentAt", System.currentTimeMillis())
                .putInt("sentCount", p.getInt("sentCount", 0) + ok)
                .putString("lastError", lastDenied ? "Not allowed — is the account active?" : "").apply();
            if (lastDenied && ok == 0) {   // everything refused: account deactivated / no longer a salesperson
                endDay(this, "denied");
                return;
            }
            updateNotification();
            if (rest(p) > 0) flush();
        } catch (java.io.IOException e) {
            p.edit().putString("lastError", "Offline — will send when online (" + rest(p) + " waiting)").apply();
        } catch (Exception e) {
            Log.w(TAG, "flush", e);
            p.edit().putString("lastError", e.getClass().getSimpleName()).apply();
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }

    private static int commit(String url, JSONArray writes, String token) throws Exception {
        String[] r = http(url, "application/json", new JSONObject().put("writes", writes).toString(), token);
        if (!"200".equals(r[0])) Log.w(TAG, "commit " + r[0] + " " + (r[1].length() > 300 ? r[1].substring(0, 300) : r[1]));
        return Integer.parseInt(r[0]);
    }

    static int rest(SharedPreferences p) {
        try { return new JSONArray(p.getString("queue", "[]")).length(); } catch (Exception e) { return 0; }
    }

    private String token(SharedPreferences p) throws Exception {
        if (idToken != null && System.currentTimeMillis() < idTokenExp - 120_000) return idToken;
        String rt = p.getString("refreshToken", null), key = p.getString("apiKey", null);
        if (rt == null || key == null) { p.edit().putString("lastError", "Not signed in").apply(); return null; }
        String[] r = http(tokenUrl(p) + URLEncoder.encode(key, "UTF-8"),
            "application/x-www-form-urlencoded", "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"), null);
        int code = Integer.parseInt(r[0]);
        if (code == 400 || code == 403) {   // refresh token revoked / user disabled / password changed
            p.edit().putString("lastError", "Signed out — open the app and sign in again").apply();
            endDay(this, "token-revoked");
            return null;
        }
        if (code != 200) { p.edit().putString("lastError", "Token " + code).apply(); return null; }
        JSONObject j = new JSONObject(r[1]);
        idToken = j.getString("id_token");
        idTokenExp = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L;
        String nrt = j.optString("refresh_token", rt);
        if (!nrt.equals(rt)) p.edit().putString("refreshToken", nrt).apply();
        return idToken;
    }

    static JSONObject fields(JSONObject d) throws Exception {
        JSONObject f = new JSONObject();
        java.util.Iterator<String> it = d.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = d.get(k);
            JSONObject val = new JSONObject();
            if (v instanceof Boolean) val.put("booleanValue", v);
            else if (v instanceof Integer || v instanceof Long) val.put("integerValue", String.valueOf(v));
            else if (v instanceof Number) val.put("doubleValue", ((Number) v).doubleValue());
            else val.put("stringValue", String.valueOf(v));
            f.put(k, val);
        }
        return f;
    }

    static String[] http(String url, String type, String body, String bearer) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20_000); c.setReadTimeout(30_000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) { byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) > 0) bo.write(buf, 0, r); in.close(); }
            return new String[]{String.valueOf(code), bo.toString("UTF-8")};
        } finally { c.disconnect(); }
    }

    static boolean shouldAutoStop(SharedPreferences p) {
        if (!today().equals(p.getString("day", ""))) return true;            // a new day: must start again
        int stopAt = p.getInt("stopHour", 21);
        return stopAt > 0 && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= stopAt;
    }

    static void endDay(Context c, String why) {
        SharedPreferences p = prefs(c);
        p.edit().putBoolean("active", false).putBoolean("running", false).putString("endReason", why)
            .putLong("endedAt", System.currentTimeMillis()).apply();
        if (c instanceof TrackingService) {
            TrackingService s = (TrackingService) c;
            // last chance to send what is queued
            Runnable stop = () -> {
                try { ServiceCompat.stopForeground(s, ServiceCompat.STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
                s.stopSelf();
            };
            try {
                if (!"denied".equals(why) && !"token-revoked".equals(why)) s.net.execute(s::flush);
                s.net.execute(stop);
            } catch (Exception e) { stop.run(); }
        } else {
            c.stopService(new Intent(c, TrackingService.class));
        }
    }

    private Notification buildNotification(String extra) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while your work day is started and your location is shared with Lalit Enterprises");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences p = prefs(this);
        int stopAt = p.getInt("stopHour", 21);
        String text = extra != null ? extra : "Work day started · auto-stops at " + (stopAt > 12 ? (stopAt - 12) + " PM" : stopAt + " AM");
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tracking)
            .setColor(0xFFF5A524)
            .setContentTitle("Lalit Enterprises — location sharing on")
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\nलोकेशन शेयरिंग चालू है। दिन खत्म होने पर ऐप में \"End day\" दबाएँ।"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification() {
        try {
            SharedPreferences p = prefs(this);
            String t = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(p.getLong("lastSentAt", System.currentTimeMillis())));
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification("Last sent " + t + " · " + p.getInt("sentCount", 0) + " updates today"));
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (fused != null && callback != null) fused.removeLocationUpdates(callback);
        callback = null;
        prefs(this).edit().putBoolean("running", false).apply();
        net.shutdown();
        super.onDestroy();
    }

    /** User swiped the app away: keep running (the foreground service is independent of the screen). */
    @Override
    public void onTaskRemoved(Intent rootIntent) { super.onTaskRemoved(rootIntent); }
}

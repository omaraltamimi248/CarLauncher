package com.omar.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.List;

public class MainActivity extends Activity {

    private static final float MOVE_KMH = 2f;
    private static final float MOVE_METERS = 6f;
    private static final long DRIVE_EXIT_MS = 5 * 60 * 1000L;
    private static final long FIX_STALE_MS = 10_000L;

    static MainActivity instance;
    private WebView web;
    private LocationManager lm;
    private LocationListener locListener;
    private String currentState = "";
    private float speedKmh = -1f;
    private float bearingDeg = -1f;        // آخر اتجاه صالح — يبقى محفوظ عند السرعة صفر
    private long lastFixAt = 0;
    private long lastMoveAt = 0;
    private boolean driveActive = false;
    private boolean manualPark = false;
    private boolean hasLastFix = false;
    private double lastLat = 0, lastLon = 0;
    private String forcedState = null;
    private static final String BT_NODE = "/sys/devices/platform/bt-ctrl/nPOEN";
    private boolean btWantOff = false;
    private MediaPlayer sfxPlayer;
    private FrameLayout rootLayer;
    private SurfaceView hudBox;
    private VirtualDisplay hudVd;
    private int hudBoxDisplayId = -1;
    private WebView overlayWeb;
    private WindowManager overlayWm;
    private View activityView;      // android.app.ActivityView (API 29 فقط)
    private Object activityViewObj;
    private float adhanVol = 0.8f;
    private boolean duckOn = true;
    private AudioManager am;
    private AudioManager.OnAudioFocusChangeListener afListener;
    private volatile int netState = -1;    // 2 متصل · 1 شبكة بلا إنترنت · 0 لا شبكة

    /** null = ما قدرنا نقرأ العقدة */
    private Boolean readBtNode() {
        try {
            File f = new File(BT_NODE);
            if (!f.exists()) return null;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String v = r.readLine(); r.close();
            return v != null && v.trim().startsWith("1");
        } catch (Exception e) { return null; }
    }

    /** يفصل تغذية الشريحة فعلياً لو المستخدم طفّى البلوتوث ورجّعها الستاك */
    private final Runnable btTick = new Runnable() {
        @Override public void run() {
            final Boolean v = readBtNode();
            if (btWantOff && v != null && v) {
                android.util.Log.d("CarLauncher", "bt enforce off");
                new Thread(new Runnable() { public void run() { adbLocal("echo 0 > " + BT_NODE); } }).start();
            }
            handler.postDelayed(this, 10000);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateState();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable netTick = new Runnable() {
        @Override public void run() {
            new Thread(new Runnable() {
                @Override public void run() { netState = checkNet(); }
            }).start();
            handler.postDelayed(this, 60000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(true);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                currentState = "";
                updateState();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("https://tiles.local/")) {
                    try {
                        String[] p = u.substring("https://tiles.local/".length()).split("/");
                        if (p.length >= 4) {
                            File f = new File(tileDir(p[0], Integer.parseInt(p[1])),
                                    p[2] + "_" + p[3].replace(".png", "") + ".png");
                            if (f.exists() && f.length() > 0) {
                                return new WebResourceResponse("image/png", null, new FileInputStream(f));
                            }
                        }
                    } catch (Exception ignored) {}
                    return new WebResourceResponse("image/png", null, null); // فاضي → JS يرجع للنت
                }
                return null;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.d("CarLauncherJS", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        rootLayer = new FrameLayout(this);
        rootLayer.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(rootLayer);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.loadUrl("file:///android_asset/ui.html?v=" + System.currentTimeMillis());

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        startGps();

        handler.post(tick);
        handler.post(netTick);
        try { btWantOff = getSharedPreferences("ui", 0).getBoolean("bt_off", false); } catch (Exception ignored) {}
        try {
            adhanVol = 1f;
            duckOn = getSharedPreferences("ui", 0).getBoolean("adhan_duck", true);
        } catch (Exception ignored) {}
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        afListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override public void onAudioFocusChange(int change) { }
        };
        handler.postDelayed(btTick, 10000);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void startGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            locListener = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (l == null) return;
                    long now = System.currentTimeMillis();
                    boolean moved = false;

                    float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : -1f;
                    if (kmh >= MOVE_KMH) moved = true;

                    if (hasLastFix) {
                        float[] d = new float[3];
                        Location.distanceBetween(lastLat, lastLon, l.getLatitude(), l.getLongitude(), d);
                        if (d[0] >= MOVE_METERS) moved = true;
                        if (moved && kmh < 1f) bearingDeg = (d[2] + 360f) % 360f;
                    }
                    if (l.hasBearing() && kmh >= 1f) bearingDeg = (l.getBearing() + 360f) % 360f;

                    lastLat = l.getLatitude();
                    lastLon = l.getLongitude();
                    hasLastFix = true;
                    lastFixAt = now;
                    speedKmh = (kmh >= 0f) ? kmh : (moved ? MOVE_KMH : 0f);
                    if (moved) lastMoveAt = now;
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper());
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                lastLat = last.getLatitude(); lastLon = last.getLongitude();
                hasLastFix = true; lastFixAt = System.currentTimeMillis();
                if (last.hasBearing()) bearingDeg = (last.getBearing() + 360f) % 360f;
            }
        } catch (Exception ignored) {}
    }

    private void updateState() {
        long now = System.currentTimeMillis();
        boolean fresh = lastFixAt > 0 && (now - lastFixAt) < FIX_STALE_MS;
        float shown = fresh ? Math.max(0f, speedKmh) : 0f;
        boolean moving = fresh && lastMoveAt > 0 && (now - lastMoveAt) < 4000;

        if (manualPark && (now - lastMoveAt) > 10_000L) manualPark = false;
        if (moving && !manualPark) driveActive = true;
        if (driveActive && (now - lastMoveAt) >= DRIVE_EXIT_MS) driveActive = false;
        if (moving && forcedState != null) forcedState = null;

        String next;
        if (manualPark) next = "park";
        else if (forcedState != null) next = forcedState;
        else if (driveActive) next = "drive";
        else next = "park";

        if (!next.equals(currentState)) {
            currentState = next;
            callJs("setCarState('" + next + "'," + Math.round(shown) + ")");
        } else if (next.equals("drive")) {
            callJs("setSpeed(" + Math.round(shown) + ")");
        }
    }

    void setTheme(String t) {
        if (t == null) return;
        callJs("setTheme('" + t + "')");
    }

    void setHeadingTest(float deg) {
        bearingDeg = (deg + 360f) % 360f;
        callJs("setHeadingTest(" + deg + ")");
    }

    void startPack() {
        new Bridge().startPack();
    }

    void forceState(String st) {
        if (st == null) return;
        long now = System.currentTimeMillis();
        if ("drive".equals(st)) {
            forcedState = null; manualPark = false; driveActive = true; lastMoveAt = now;
        } else if ("park".equals(st)) {
            forcedState = null; manualPark = true; driveActive = false; lastMoveAt = now;
        } else {
            forcedState = null; manualPark = false;
        }
        currentState = "";
        updateState();
    }

    private void callJs(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(netTick);
        handler.removeCallbacks(btTick);
        try {
            if (lm != null && locListener != null) lm.removeUpdates(locListener);
        } catch (Exception ignored) {}
        locListener = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /* ---------- الشبكة ---------- */
    private int checkNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return 0;
            HttpURLConnection c = (HttpURLConnection) new URL("https://clients3.google.com/generate_204").openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000); c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            c.disconnect();
            return code > 0 ? 2 : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /* ---------- بلاطات أوفلاين ---------- */
    private File tileDir(String theme, int z) {
        File d = new File(getFilesDir(), "tiles/" + theme + "/" + z);
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private String esriStyle(String theme) {
        return "light".equals(theme) ? "World_Light_Gray_Base" : "World_Dark_Gray_Base";
    }
    private boolean tileExists(String theme, int z, int x, int y) {
        File f = new File(tileDir(theme, z), x + "_" + y + ".png");
        return f.exists() && f.length() > 0;
    }
    private int countTiles(String theme) {
        int n = 0;
        File[] zs = new File(getFilesDir(), "tiles/" + theme).listFiles();
        if (zs == null) return 0;
        for (File z : zs) {
            File[] fs = z.listFiles();
            if (fs != null) n += fs.length;
        }
        return n;
    }

    private void downloadTile(String theme, int z, int x, int y) {
        File f = new File(tileDir(theme, z), x + "_" + y + ".png");
        if (f.exists() && f.length() > 0) return;
        try {
            String u = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/" + esriStyle(theme)
                    + "/MapServer/tile/" + z + "/" + y + "/" + x;
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "CarLauncher/1.0");
            InputStream in = c.getInputStream();
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.close(); in.close(); c.disconnect();
            if (tmp.length() > 500) tmp.renameTo(f); else tmp.delete();
        } catch (Exception ignored) {}
    }

    private static double lon2x(double lon, int z) {
        return (lon + 180.0) / 360.0 * (1 << z);
    }
    private static double lat2y(double lat, int z) {
        double s = Math.sin(lat * Math.PI / 180.0);
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * (1 << z);
    }

    /* ---------- عميل ADB محلي (127.0.0.1:5555) ---------- */
    private static void sendPkt(DataOutputStream o, int cmd, int a0, int a1, byte[] d) throws Exception {
        int sum = 0;
        for (byte b : d) sum += (b & 0xff);
        o.writeInt(Integer.reverseBytes(cmd));
        o.writeInt(Integer.reverseBytes(a0));
        o.writeInt(Integer.reverseBytes(a1));
        o.writeInt(Integer.reverseBytes(d.length));
        o.writeInt(Integer.reverseBytes(sum));
        o.writeInt(Integer.reverseBytes(cmd ^ 0xffffffff));
        if (d.length > 0) o.write(d);
        o.flush();
    }

    private static int[] readHdr(DataInputStream in) throws Exception {
        int cmd = Integer.reverseBytes(in.readInt());
        int a0 = Integer.reverseBytes(in.readInt());
        int a1 = Integer.reverseBytes(in.readInt());
        int len = Integer.reverseBytes(in.readInt());
        in.readInt();  // checksum
        in.readInt();  // magic
        return new int[]{cmd, a0, a1, len};
    }

    /** يشغّل ملف صوت من assets (adhan.ogg / iqama.ogg) */
    private void playAsset(final String asset) {
        new Thread(new Runnable() { public void run() {
            try {
                if (sfxPlayer != null) { try { sfxPlayer.release(); } catch (Exception ignored) {} sfxPlayer = null; }
                AssetFileDescriptor afd = getAssets().openFd(asset);
                final MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer m) {
                        try { m.release(); } catch (Exception ignored) {}
                        if (duckOn && am != null) { try { am.abandonAudioFocus(afListener); } catch (Exception ignored) {} }
                    }
                });
                mp.prepare();
                mp.setVolume(adhanVol, adhanVol);
                if (duckOn && am != null) {
                    try { am.requestAudioFocus(afListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); } catch (Exception ignored) {}
                }
                mp.start();
                sfxPlayer = mp;
                android.util.Log.d("CarLauncher", "sound " + asset);
            } catch (Exception e) {
                android.util.Log.d("CarLauncher", "sound failed " + asset + ": " + e);
            }
        }}).start();
    }

    private void stopSfx() {
        new Thread(new Runnable() { public void run() {
            try {
                if (sfxPlayer != null) { sfxPlayer.stop(); sfxPlayer.release(); sfxPlayer = null; }
                if (duckOn && am != null) { try { am.abandonAudioFocus(afListener); } catch (Exception ignored) {} }
            } catch (Exception ignored) {}
        }}).start();
    }

    /** اختبار الإشعار والصوت من خلال بث */
    public void testAdhan() {
        runOnUiThread(new Runnable() { public void run() {
            if (web != null) web.evaluateJavascript("testAdhan()", null);
        }});
    }

    public void testIqama() { playAsset("iqama.ogg"); }

    /** يرسل مفتاح صوتيات (85 تشغيل/إيقاف · 87 التالي · 88 السابق) */
    private void mediaKey(final int code) {
        new Thread(new Runnable() { public void run() { adbLocal("input keyevent " + code); } }).start();
    }

    /** ينفّذ أمر شل كـroot عبر adbd المحلي. يرجع المخرجات أو null. */
    private String adbLocal(String command) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(8000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            sendPkt(out, 0x4e584e43, 0x01000000, 1024 * 1024, "host::\u0000".getBytes("UTF-8"));
            int[] h = readHdr(in);
            if (h[3] > 0) in.readFully(new byte[h[3]]);
            android.util.Log.d("CarLauncher", "adb handshake=" + Integer.toHexString(h[0]));
            if (h[0] != 0x4e584e43) return null;   // AUTH أو رفض

            int lid = 1;
            sendPkt(out, 0x4e45504f, lid, 0, ("shell,v2,raw:" + command + "\u0000").getBytes("UTF-8"));
            h = readHdr(in);
            android.util.Log.d("CarLauncher", "adb open=" + Integer.toHexString(h[0]));
            if (h[0] != 0x59414b4f) return null;
            int rid = h[1];

            StringBuilder sb = new StringBuilder();
            boolean done = false;
            while (!done) {
                h = readHdr(in);
                int n = h[3];
                byte[] d = new byte[n];
                if (n > 0) in.readFully(d);
                if (h[0] == 0x45545257) {                 // WRTE
                    sendPkt(out, 0x59414b4f, lid, rid, new byte[0]);
                    int i = 0;
                    while (i + 5 <= d.length) {
                        int sid = d[i] & 0xff;
                        int ln = (d[i + 1] & 0xff) | ((d[i + 2] & 0xff) << 8) | ((d[i + 3] & 0xff) << 16) | ((d[i + 4] & 0xff) << 24);
                        int start = i + 5, end = Math.min(start + ln, d.length);
                        if (sid == 1) sb.append(new String(d, start, end - start, "UTF-8"));
                        if (sid == 3) { done = true; break; }
                        i = end;
                    }
                } else if (h[0] == 0x454e4c43) {          // CLSE
                    break;
                }
            }
            try { sendPkt(out, 0x454e4c43, lid, rid, new byte[0]); } catch (Exception ignored) {}
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
    }

    /* ---------- جسر للـ JavaScript ---------- */
    /* ---------- هدهد داخل مربع (VirtualDisplay) ---------- */
    private void boxSend(final String cmd) {
        final int id = hudBoxDisplayId;
        if (id < 0) return;
        new Thread(new Runnable() { public void run() {
            try { adbLocal("input -d " + id + " " + cmd); } catch (Exception ignored) {}
        }}).start();
    }

    /* ---------- بطاقة عائمة فوق هدهد ---------- */
    void showOverlayPanel() {
        try {
            if (overlayWeb != null) return;
            if (!Settings.canDrawOverlays(this)) {
                try {
                    Intent si = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(si);
                    toast("اسمح بالرسم فوق التطبيقات ثم اضغط الزر مرة ثانية");
                } catch (Exception ignored) {}
                return;
            }
            WebView w = new WebView(this);
            WebSettings ws = w.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setAllowFileAccess(true);
            w.setBackgroundColor(Color.TRANSPARENT);
            w.addJavascriptInterface(new Bridge(), "Native");
            w.loadUrl("file:///android_asset/ui.html");

            overlayWm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    340, 540,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.RIGHT;
            lp.x = 14; lp.y = 30;
            overlayWm.addView(w, lp);
            overlayWeb = w;
            android.util.Log.d("CarLauncher", "overlay added");
        } catch (Exception e) {
            android.util.Log.d("CarLauncher", "overlay fail " + e);
        }
    }

    void hideOverlayPanel() {
        try {
            if (overlayWeb != null && overlayWm != null) overlayWm.removeView(overlayWeb);
        } catch (Exception ignored) {}
        overlayWeb = null;
        try {
            Intent mi = new Intent(this, MainActivity.class);
            mi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(mi);
        } catch (Exception ignored) {}
    }

    /* المحاولة ١: ActivityView — واجهة أندرويد ١٠ لتضمين تطبيق داخل عرض */
    private boolean tryActivityView() {
        try {
            final Class<?> c = Class.forName("android.app.ActivityView");
            activityViewObj = c.getConstructor(Context.class).newInstance(this);
            activityView = (View) activityViewObj;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(664, 518);
            lp.leftMargin = 30; lp.topMargin = 58;
            rootLayer.addView(activityView, lp);

            final Intent i = getPackageManager().getLaunchIntentForPackage("sa.hudhud.maps");
            if (i == null) { releaseActivityView(); return false; }
            final java.lang.reflect.Method startM = c.getMethod("startActivity", Intent.class);

            new Thread(new Runnable() { public void run() {
                for (int n = 0; n < 25; n++) {
                    try {
                        Thread.sleep(150);
                        startM.invoke(activityViewObj, i);
                        android.util.Log.d("CarLauncher", "activityview started after " + n);
                        return;
                    } catch (Throwable t) {
                        Throwable ca = (t.getCause() == null ? t : t.getCause());
                        if (!(ca instanceof IllegalStateException)) {
                            android.util.Log.d("CarLauncher", "activityview start err " + ca);
                            runOnUiThread(new Runnable() { public void run() {
                                releaseActivityView();
                                try {
                                    Intent k = getPackageManager().getLaunchIntentForPackage("sa.hudhud.maps");
                                    if (k != null) { k.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(k); }
                                } catch (Exception ignored) {}
                            }});
                            return;
                        }
                    }
                }
                android.util.Log.d("CarLauncher", "activityview never ready");
            }}).start();
            return true;
        } catch (Throwable t) {
            android.util.Log.d("CarLauncher", "activityview fail " + t);
            releaseActivityView();
            return false;
        }
    }

    private void releaseActivityView() {
        if (activityViewObj != null) {
            try { activityViewObj.getClass().getMethod("release").invoke(activityViewObj); } catch (Throwable ignored) {}
            activityViewObj = null;
        }
        if (activityView != null && rootLayer != null) {
            try { rootLayer.removeView(activityView); } catch (Throwable ignored) {}
            activityView = null;
        }
    }

    void openBox() {
        if (hudVd != null || rootLayer == null) return;
        if (activityView != null) return;
        if (tryActivityView()) return;
        final int bx = 30, by = 58, bw = 664, bh = 518;
        hudBox = new SurfaceView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bw, bh);
        lp.leftMargin = bx; lp.topMargin = by;
        hudBox.setZOrderMediaOverlay(true);
        rootLayer.addView(hudBox, lp);
        hudBox.setOnTouchListener(new View.OnTouchListener() {
            long lastMove = 0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                int x = (int) e.getX(), y = (int) e.getY();
                if (a == MotionEvent.ACTION_DOWN) {
                    lastMove = 0; boxSend("motionevent DOWN " + x + " " + y);
                } else if (a == MotionEvent.ACTION_MOVE) {
                    long now = System.currentTimeMillis();
                    if (now - lastMove > 90) { lastMove = now; boxSend("motionevent MOVE " + x + " " + y); }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    boxSend("motionevent UP " + x + " " + y);
                }
                return true;
            }
        });
        hudBox.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                try {
                    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                    hudVd = dm.createVirtualDisplay("hudbox", bw, bh, 160, h.getSurface(),
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
                    hudBoxDisplayId = hudVd.getDisplay().getDisplayId();
                    android.util.Log.d("CarLauncher", "vd created id=" + hudBoxDisplayId);
                    final int id = hudBoxDisplayId;
                    new Thread(new Runnable() { public void run() {
                        try {
                            Thread.sleep(700);
                            adbLocal("am start --display " + id + " -n sa.hudhud.maps/sa.hudhud.map.ui.MainActivity");
                        } catch (Exception e) {
                            android.util.Log.d("CarLauncher", "vd launch fail " + e);
                        }
                    }}).start();
                } catch (Exception e) {
                    android.util.Log.d("CarLauncher", "vd create fail " + e);
                    closeBoxInternal(false);
                    try {
                        Intent k = getPackageManager().getLaunchIntentForPackage("sa.hudhud.maps");
                        if (k != null) { k.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(k); }
                    } catch (Exception e2) {}
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { closeBoxInternal(false); }
        });
    }

    void closeBoxInternal(boolean killApp) {
        releaseActivityView();
        if (hudVd != null) { try { hudVd.release(); } catch (Exception ignored) {} hudVd = null; }
        hudBoxDisplayId = -1;
        if (hudBox != null && rootLayer != null) {
            try { rootLayer.removeView(hudBox); } catch (Exception ignored) {}
            hudBox = null;
        }
        if (killApp) new Thread(new Runnable() { public void run() {
            try { adbLocal("am force-stop sa.hudhud.maps"); } catch (Exception ignored) {}
        }}).start();
    }

    void closeBox() { runOnUiThread(new Runnable() { public void run() { closeBoxInternal(true); } }); }

    private class Bridge {
        @JavascriptInterface
        public String pos() {
            if (!hasLastFix) return "";
            return String.format(java.util.Locale.US, "%.6f,%.6f", lastLat, lastLon);
        }

        @JavascriptInterface
        public int speed() { return Math.max(0, Math.round(speedKmh)); }

        @JavascriptInterface
        public int heading() { return Math.round(bearingDeg); }

        @JavascriptInterface
        public int netState() { return netState; }

        @JavascriptInterface
        public boolean btOn() {
            // بلوتوث هذه الشاشة يديره الستاك الصانع (nwdbt) مو Android — نقرأ حالة الطاقة الفعلية
            Boolean v = readBtNode();
            if (v != null) return v;
            try {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                return a != null && a.isEnabled();
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean btToggle() {
            android.util.Log.d("CarLauncher", "btToggle called");
            Boolean cur = readBtNode();
            if (cur == null) {                    // احتياط: اقرأها عبر adb
                String s = adbLocal("cat " + BT_NODE);
                cur = s != null && s.trim().startsWith("1");
            }
            final boolean wantOff = cur;          // شغّال؟ → المطلوب إطفاء
            btWantOff = wantOff;
            try { getSharedPreferences("ui", 0).edit().putBoolean("bt_off", wantOff).apply(); } catch (Exception ignored) {}
            new Thread(new Runnable() { public void run() {
                String res;
                if (wantOff) {
                    res = adbLocal("echo 0 > " + BT_NODE + "; sleep 1; cat " + BT_NODE);
                } else {
                    res = adbLocal("sh /system/bin/chmod_bt_ctrl.sh; echo 1 > " + BT_NODE + "; am broadcast -a com.nwd.btbroad.service.ACTION_START -p com.nwd.android.phone >/dev/null 2>&1; sleep 2; cat " + BT_NODE);
                    Boolean v = readBtNode();
                    if (v == null || !v) {          // محاولة ثانية: البث وحده
                        res = adbLocal("sh /system/bin/chmod_bt_ctrl.sh; sleep 1; echo 1 > " + BT_NODE + "; sleep 1; am broadcast -a com.nwd.btbroad.service.ACTION_START -p com.nwd.android.phone >/dev/null 2>&1; sleep 2; cat " + BT_NODE);
                    }
                }
                android.util.Log.d("CarLauncher", "btToggle res=" + res);
            }}).start();
            return !wantOff;
        }

        @JavascriptInterface
        public boolean wifiOn() {
            String s = adbLocal("settings get global wifi_on");
            return s != null && s.trim().startsWith("1");
        }

        @JavascriptInterface
        public boolean wifiToggle() {
            final boolean on = wifiOn();
            new Thread(new Runnable() { public void run() {
                adbLocal(on ? "svc wifi disable" : "svc wifi enable");
            }}).start();
            return !on;
        }

        @JavascriptInterface public void adhanSound() { playAsset("adhan.ogg"); }
        @JavascriptInterface public void iqamaSound() { playAsset("iqama.ogg"); }
        @JavascriptInterface public void stopAdhan() { stopSfx(); }

        @JavascriptInterface public void mediaPrev() { mediaKey(88); }
        @JavascriptInterface public void mediaNext() { mediaKey(87); }
        @JavascriptInterface public void mediaToggle() { mediaKey(85); }

        @JavascriptInterface
        public int btState() {
            try {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null) return 0;
                if (a.isEnabled()) return 2;
                int st = a.getState();
                return (st == BluetoothAdapter.STATE_TURNING_ON || st == BluetoothAdapter.STATE_TURNING_OFF) ? 1 : 0;
            } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public void toastMsg(String m) { toast(m); }

        @JavascriptInterface
        public String tile(String theme, int z, int x, int y) {
            return tileExists(theme, z, x, y) ? "https://tiles.local/" + theme + "/" + z + "/" + x + "/" + y + ".png" : "";
        }

        @JavascriptInterface
        public void cacheTile(final String theme, final int z, final int x, final int y) {
            new Thread(new Runnable() { public void run() { downloadTile(theme, z, x, y); } }).start();
        }

        @JavascriptInterface
        public String packInfo() {
            return countTiles("dark") + "/" + countTiles("light");
        }

        @JavascriptInterface
        public void startPack() {
            new Thread(new Runnable() {
                @Override public void run() {
                    final int Z = 16;
                    double lat = 27.5236, lon = 41.6925;
                    double dLat = 0.180, dLon = 0.206;
                    int x0 = (int) Math.floor(lon2x(lon - dLon, Z));
                    int x1 = (int) Math.ceil(lon2x(lon + dLon, Z));
                    int y0 = (int) Math.floor(lat2y(lat + dLat, Z));
                    int y1 = (int) Math.ceil(lat2y(lat - dLat, Z));
                    String[] themes = {"dark", "light"};
                    java.util.ArrayList<int[]> jobs = new java.util.ArrayList<>();
                    java.util.ArrayList<String> ths = new java.util.ArrayList<>();
                    for (String th : themes) {
                        for (int x = x0; x <= x1; x++) {
                            for (int y = y0; y <= y1; y++) {
                                jobs.add(new int[]{x, y});
                                ths.add(th);
                            }
                        }
                    }
                    final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(0);
                    int workers = 6;
                    Thread[] ws = new Thread[workers];
                    for (int w = 0; w < workers; w++) {
                        ws[w] = new Thread(new Runnable() {
                            @Override public void run() {
                                while (true) {
                                    int i = idx.getAndIncrement();
                                    if (i >= jobs.size()) return;
                                    int[] p = jobs.get(i);
                                    downloadTile(ths.get(i), Z, p[0], p[1]);
                                }
                            }
                        });
                        ws[w].start();
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void setMediaVolume(int pct) {
            try {
                AudioManager a = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int idx = Math.round((pct < 0 ? 0 : (pct > 100 ? 100 : pct)) / 100f * max);
                a.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
                android.util.Log.d("CarLauncher", "vol set pct=" + pct + " idx=" + idx + " max=" + max);
            } catch (Exception e) { android.util.Log.d("CarLauncher", "vol err " + e); }
        }

        @JavascriptInterface
        public int mediaVolume() {
            try {
                AudioManager a = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                return Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
            } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public void setAdhanVolume(int pct) {
            if (pct < 0) pct = 0; if (pct > 100) pct = 100;
            adhanVol = pct / 100f;
            try { getSharedPreferences("ui", 0).edit().putInt("adhan_vol", pct).apply(); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public int adhanVolume() { return Math.round(adhanVol * 100); }

        @JavascriptInterface
        public void setDuck(boolean on) {
            duckOn = on;
            if (!on && am != null) { try { am.abandonAudioFocus(afListener); } catch (Exception ignored) {} }
            try { getSharedPreferences("ui", 0).edit().putBoolean("adhan_duck", on).apply(); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean duckEnabled() { return duckOn; }

        @JavascriptInterface
        public boolean boxOpen() { return hudVd != null || activityView != null || overlayWeb != null; }

        @JavascriptInterface
        public void toggleBox() {
            runOnUiThread(new Runnable() { public void run() {
                if (overlayWeb != null) { hideOverlayPanel(); return; }
                showOverlayPanel();
                try {
                    Intent k = getPackageManager().getLaunchIntentForPackage("sa.hudhud.maps");
                    if (k != null) { k.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(k); }
                } catch (Exception ignored) {}
            }});
        }

        @JavascriptInterface
        public void closeOverlay() {
            runOnUiThread(new Runnable() { public void run() { hideOverlayPanel(); } });
        }

        @JavascriptInterface
        public void openSplit() {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String out = adbLocal("am start --windowingMode 3 -n sa.hudhud.maps/sa.hudhud.map.ui.MainActivity");
                        android.util.Log.d("CarLauncher", "split: " + out);
                        if (out == null || out.contains("Error") || out.contains("Exception")) {
                            final Intent k = getPackageManager().getLaunchIntentForPackage("sa.hudhud.maps");
                            if (k != null) {
                                k.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                runOnUiThread(new Runnable() { public void run() { startActivity(k); } });
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.d("CarLauncher", "split failed " + e);
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public String ip() {
            try {
                java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
                while (ifs.hasMoreElements()) {
                    java.net.NetworkInterface ni = ifs.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String n = ni.getName();
                    if (n == null || (!n.startsWith("wlan") && !n.startsWith("eth"))) continue;
                    java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                    while (as.hasMoreElements()) {
                        java.net.InetAddress a = as.nextElement();
                        if (a instanceof java.net.Inet4Address) return a.getHostAddress();
                    }
                }
            } catch (Exception e) {}
            return "";
        }

        @JavascriptInterface
        public String version() {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return pi.versionName + " · " + new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(pi.lastUpdateTime));
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void setBrightness(final float v) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v;
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public void restart() {
            runOnUiThread(new Runnable() {
                @Override public void run() { recreate(); }
            });
        }

        @JavascriptInterface
        public String icon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                int sz = 96;
                Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, sz, sz);
                d.draw(c);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, bo);
                return "data:image/png;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                android.util.Log.d("CarLauncher", "icon err " + pkg + " " + e);
                return "";
            }
        }

        @JavascriptInterface
        public String apps() {
            StringBuilder sb = new StringBuilder("[");
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
                boolean first = true;
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    String label = ri.loadLabel(getPackageManager()).toString();
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"p\":\"").append(pkg.replace("\"", "")).append("\",\"n\":\"")
                      .append(label.replace("\\", "").replace("\"", "")).append("\"}");
                    if (sb.length() > 4000) break;
                }
            } catch (Exception ignored) {}
            sb.append("]");
            android.util.Log.d("CarLauncher", "apps=" + sb.toString());
            return sb.toString();
        }

        @JavascriptInterface
        public void openApp(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } else {
                    toast("التطبيق غير مثبت: " + pkg);
                }
            } catch (Exception e) {
                toast("ما قدرت أفتح التطبيق");
            }
        }

        @JavascriptInterface
        public void openMusic() {
            final String pkg = "com.google.android.apps.youtube.music";
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/library/downloads"));
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } else {
                    toast("يوتيوب ميوزيك غير مثبت");
                }
            } catch (Exception e) {
                toast("ما فتحت يوتيوب ميوزيك");
            }
        }

        @JavascriptInterface
        public void openUri(String uri) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("ما قدرت أفتح الرابط");
            }
        }

        @JavascriptInterface
        public void forceState(final String st) {
            runOnUiThread(new Runnable() {
                @Override public void run() { MainActivity.this.forceState(st); }
            });
        }

        @JavascriptInterface
        public void map() {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
                i.setPackage("com.google.android.apps.maps");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("الخريطة غير متوفرة");
            }
        }

        @JavascriptInterface
        public void dial() {
            try {
                Intent i = new Intent(Intent.ACTION_DIAL);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) { toast("لا يوجد تطبيق هاتف"); }
        }

        @JavascriptInterface
        public void settings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) { toast("ما فتحت الإعدادات"); }
        }

        @JavascriptInterface
        public void stockLauncher() {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                ComponentName cn = new ComponentName("com.android.launcher.theme", "com.android.launcher.theme.Launcher");
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) { toast("ما فتحت اللانشر الأصلي"); }
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}

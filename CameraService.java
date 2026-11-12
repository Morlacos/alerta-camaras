package la.alertacamaras;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;

public class CameraService extends Service implements LocationListener, TextToSpeech.OnInitListener {
    public static volatile boolean running = false;
    private static final String CH_STATUS = "estado";
    private static final String CH_ALERT = "alertas";
    private static final int NID = 1;

    private int n = 0;
    private double[] lat, lng;
    private String[] street, from, to;
    private int[] limit;

    private int radius = 400;
    private boolean voice = true;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ToneGenerator tone;
    private LocationManager lm;
    private NotificationManager nm;
    private final HashMap<Integer, Integer> level = new HashMap<>();
    private long lastNotif = 0;
    private Location prev = null;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel s = new NotificationChannel(CH_STATUS, "Estado", NotificationManager.IMPORTANCE_LOW);
            NotificationChannel a = new NotificationChannel(CH_ALERT, "Alertas de cámara", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(s);
            nm.createNotificationChannel(a);
        }
        loadCams();
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100); } catch (Exception e) { tone = null; }
    }

    private void loadCams() {
        try {
            InputStream in = getAssets().open("cams.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            JSONArray arr = new JSONArray(bo.toString("UTF-8"));
            n = arr.length();
            lat = new double[n]; lng = new double[n];
            street = new String[n]; from = new String[n]; to = new String[n];
            limit = new int[n];
            for (int i = 0; i < n; i++) {
                JSONArray c = arr.getJSONArray(i);
                street[i] = c.getString(1);
                from[i] = c.getString(2);
                to[i] = c.getString(3);
                limit[i] = c.getInt(4);
                lat[i] = c.getDouble(5);
                lng[i] = c.getDouble(6);
            }
        } catch (Exception e) {
            n = 0;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            radius = intent.getIntExtra("r", radius);
            voice = intent.getBooleanExtra("v", voice);
        }
        Notification note = buildStatus("Buscando señal de GPS…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NID, note);
        }
        if (!running) {
            running = true;
            tts = new TextToSpeech(this, this);
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            } catch (SecurityException e) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int res = tts.setLanguage(new Locale("es", "US"));
            if (res < 0) tts.setLanguage(new Locale("es"));
            ttsReady = true;
            say("Alertas de cámaras activadas");
        }
    }

    private void say(String text) {
        if (!voice || !ttsReady || tts == null) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alerta");
    }

    private void beep(int times) {
        if (tone == null) return;
        try { tone.startTone(times > 2 ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, 400); } catch (Exception ignored) { }
    }

    private void vibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else v.vibrate(pattern, -1);
    }

    private static String fmt(float m) {
        double mi = m / 1609.34;
        if (mi < 0.1) return Math.round(m * 3.281) + " pies";
        return String.format(Locale.US, "%.1f millas", mi);
    }

    private static String fmtShort(float m) {
        double mi = m / 1609.34;
        if (mi < 0.1) return Math.round(m * 3.281) + " ft";
        return String.format(Locale.US, "%.1f mi", mi);
    }

    @Override
    public void onLocationChanged(Location l) {
        if (n == 0) return;
        double mph = -1;
        if (l.hasSpeed()) mph = l.getSpeed() * 2.23694;
        else if (prev != null && l.getTime() > prev.getTime())
            mph = l.distanceTo(prev) / ((l.getTime() - prev.getTime()) / 1000.0) * 2.23694;
        prev = l;

        float[] d = new float[n];
        float[] tmp = new float[1];
        int best = 0;
        for (int i = 0; i < n; i++) {
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), lat[i], lng[i], tmp);
            d[i] = tmp[0];
            if (d[i] < d[best]) best = i;
        }
        for (int i = 0; i < n; i++) {
            if (d[i] > radius * 1.6f) level.remove(i);
        }
        for (int i = 0; i < n; i++) {
            if (d[i] >= radius) continue;
            Integer lv = level.get(i);
            boolean over = mph > limit[i] + 10;
            if (lv == null) {
                level.put(i, 1);
                beep(2);
                vibrate(new long[]{0, 200, 100, 200});
                say("Cámara de velocidad a " + fmt(d[i]) + ", en " + street[i] + ". Límite " + limit[i] + " millas por hora.");
                alertNotification(i, d[i]);
            } else if (lv == 1 && d[i] < radius * 0.4f) {
                level.put(i, 2);
                beep(3);
                vibrate(new long[]{0, 400, 100, 400});
                say(over ? "Baja la velocidad. Límite " + limit[i] + "." : "Cámara justo adelante. Límite " + limit[i] + ".");
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastNotif > 3000) {
            lastNotif = now;
            String sp = mph >= 0 ? " · Tú: " + Math.round(mph) + " mph" : "";
            nm.notify(NID, buildStatus(street[best] + " a " + fmtShort(d[best]) + " · Límite " + limit[best] + sp));
        }
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Builder builder(String ch) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat).setContentIntent(openApp());
    }

    private Notification buildStatus(String text) {
        return builder(CH_STATUS)
                .setContentTitle("Vigilando cámaras de velocidad")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void alertNotification(int i, float dist) {
        Notification.Builder b = builder(CH_ALERT)
                .setContentTitle("📷 Cámara a " + fmtShort(dist) + " · Límite " + limit[i])
                .setContentText(street[i] + " entre " + from[i] + " y " + to[i])
                .setAutoCancel(true)
                .setTimeoutAfter(60000);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        try { nm.notify(100 + i, b.build()); } catch (Exception ignored) { }
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) {
        nm.notify(NID, buildStatus("El GPS está apagado. Actívalo para recibir avisos."));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (lm != null) { try { lm.removeUpdates(this); } catch (Exception ignored) { } }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (tone != null) { tone.release(); tone = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

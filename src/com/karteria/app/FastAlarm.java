package com.karteria.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Schedules, fires and restores the "fast complete" notification. */
public class FastAlarm extends BroadcastReceiver {
    static final String CHANNEL = "fast_timer";
    static final String PREFS = "fast_alarm";
    static final String ACTION_FIRE = "com.karteria.app.FAST_DONE";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static PendingIntent firePI(Context c) {
        Intent i = new Intent(c, FastAlarm.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canExact(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    static void schedule(Context c, long at, String title, String text) {
        prefs(c).edit().putLong("at", at).putString("title", title).putString("text", text).apply();
        arm(c, at);
    }

    static void arm(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = firePI(c);
        am.cancel(pi);
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancel(Context c) {
        prefs(c).edit().clear().apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(firePI(c));
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Fasting timer",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alerts you when you reach your fasting goal");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            long at = prefs(c).getLong("at", 0);
            if (at > System.currentTimeMillis()) arm(c, at);
            else if (at > 0) notifyDone(c); // goal passed while the phone was off
            return;
        }
        if (ACTION_FIRE.equals(action)) notifyDone(c);
    }

    static void notifyDone(Context c) {
        SharedPreferences p = prefs(c);
        String title = p.getString("title", "Fast complete");
        String text = p.getString("text", "You reached your fasting goal.");
        p.edit().clear().apply();
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_fast)
                .setColor(0xFFE2552D)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(42, n);
        } catch (SecurityException ignored) {
            // notification permission not granted
        }
    }
}

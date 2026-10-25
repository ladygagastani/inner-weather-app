package app.innerweather.log;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Morning / day / evening reminders. The page reports its reminder times and which moments are still open
 * (IWLNative.setReminderTimes / setOpenMoments); a reminder fires only if its moment is still open today.
 */
public class Reminders extends BroadcastReceiver {

    static final String[] MOMENTS = {"morning", "day", "evening"};
    static final String CHANNEL = "moments";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("iwl", Context.MODE_PRIVATE); }

    /** The log's day key: 00:00 to 03:59 still belongs to the evening before. */
    static String logDay(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(t - 4L * 3600 * 1000);
    }

    static PendingIntent alarmIntent(Context c, int i) {
        Intent it = new Intent(c, Reminders.class).setAction("app.innerweather.log.REMIND").putExtra("moment", MOMENTS[i]);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, 100 + i, it, flags);
    }

    /** (Re)schedules the next alarm for every moment that has a time. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        JSONObject times;
        try { times = new JSONObject(prefs(c).getString("reminderTimes", "{}")); } catch (Exception e) { times = new JSONObject(); }
        for (int i = 0; i < MOMENTS.length; i++) {
            PendingIntent pi = alarmIntent(c, i);
            am.cancel(pi);
            String hm = times.isNull(MOMENTS[i]) ? "" : times.optString(MOMENTS[i], "");
            if (!hm.matches("\\d{1,2}:\\d{2}")) continue;
            String[] p = hm.split(":");
            Calendar at = Calendar.getInstance();
            at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
            at.set(Calendar.MINUTE, Integer.parseInt(p[1]));
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (at.getTimeInMillis() <= System.currentTimeMillis() + 1000) at.add(Calendar.DAY_OF_MONTH, 1);
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
            else am.set(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
        }
    }

    static boolean isOpen(Context c, String moment) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString("openMoments", "{}"));
            String today = logDay(System.currentTimeMillis());
            // Only trust what the page reported today; a page that never reports (or stale data) means no reminder.
            if (!today.equals(o.optString("day"))) return false;
            JSONArray open = o.optJSONArray("open");
            if (open == null) return false;
            for (int i = 0; i < open.length(); i++) if (moment.equals(open.optString(i))) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if ("app.innerweather.log.REMIND".equals(action)) {
            String m = intent.getStringExtra("moment");
            if (m != null && isOpen(c, m)) notify(c, m);
        }
        schedule(c); // next day, and after a reboot or app update
    }

    static void notify(Context c, String moment) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Check-in reminders", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String title = moment.equals("morning") ? "Morning check-in" : moment.equals("day") ? "Daytime check-in" : "Evening check-in";
        String text = moment.equals("morning") ? "How did you sleep? A minute to start the day."
            : moment.equals("day") ? "A quick check on how the day is going."
            : "Close the day: a few questions before bed.";
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent tap = PendingIntent.getActivity(c, 200, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify).setColor(0xFF1D2C4E).setContentTitle(title).setContentText(text)
            .setContentIntent(tap).setAutoCancel(true);
        nm.notify(moment.hashCode(), b.build());
    }

    static void clear(Context c, String moment) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(moment.hashCode());
    }
}

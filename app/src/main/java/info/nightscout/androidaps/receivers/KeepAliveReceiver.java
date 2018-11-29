package info.nightscout.androidaps.receivers;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.crashlytics.android.answers.CustomEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.events.EventProfileSwitchChange;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.queue.commands.Command;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.FabricPrivacy;
import info.nightscout.utils.LocalAlertUtils;
import info.nightscout.utils.SP;
import info.nightscout.utils.T;


/**
 * Created by mike on 07.07.2016.
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    private static Logger log = LoggerFactory.getLogger(L.CORE);
    public static final long STATUS_UPDATE_FREQUENCY = T.mins(15).msecs();
    private static long lastReadStatus = 0;
    private static long lastRun = 0;

    public static void cancelAlarm(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class);
        PendingIntent sender = PendingIntent.getBroadcast(context, 0, intent, 0);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(sender);
    }

    @Override
    public void onReceive(Context context, Intent rIntent) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "");
        wl.acquire();
        checkStudy();
        LocalAlertUtils.shortenSnoozeInterval();
        LocalAlertUtils.checkStaleBGAlert();
        checkPump();
        FabricPrivacy.uploadDailyStats();

        if (L.isEnabled(L.CORE))
            log.debug("KeepAlive received");
        wl.release();
    }

    private void checkStudy() {
        if(!Config.POZNANSTUDY) return;

        //TODO poznanstudy
        long lastTDDRead = SP.getLong("lastTDDRead", 0L);
        long now = System.currentTimeMillis();
        if (now - lastTDDRead > T.days(1).msecs()) {
            if (L.isEnabled(L.CORE))
                log.debug("reading TDDs");
            ConfigBuilderPlugin.getPlugin().getCommandQueue().loadTDDs(null);
            SP.putLong("lastTDDRead", now);
        }
        /*TODO: export to SDcard as zip with date added:
            once a day?
                * Today's therapy settings?
                * Today's profile?
           Once a week?
                *  Glucose values
                *  TDDs, Basis, Bolus as list
                *  Carbs, Boluses, Treatments, ...
         */


    }

    private void checkPump() {
        final PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        final Profile profile = ProfileFunctions.getInstance().getProfile();
        if (pump != null && profile != null) {
            long lastConnection = pump.lastDataTime();
            boolean isStatusOutdated = lastConnection + STATUS_UPDATE_FREQUENCY < System.currentTimeMillis();
            boolean isBasalOutdated = Math.abs(profile.getBasal() - pump.getBaseBasalRate()) > pump.getPumpDescription().basalStep;

            if (L.isEnabled(L.CORE))
                log.debug("Last connection: " + DateUtil.dateAndTimeString(lastConnection));
            // sometimes keepalive broadcast stops
            // as as workaround test if readStatus was requested before an alarm is generated
            if (lastReadStatus != 0 && lastReadStatus > System.currentTimeMillis() - T.mins(5).msecs()) {
                LocalAlertUtils.checkPumpUnreachableAlarm(lastConnection, isStatusOutdated);
            }

            if (!pump.isThisProfileSet(profile) && !ConfigBuilderPlugin.getPlugin().getCommandQueue().isRunning(Command.CommandType.BASALPROFILE)) {
                MainApp.bus().post(new EventProfileSwitchChange());
            } else if (isStatusOutdated && !pump.isBusy()) {
                lastReadStatus = System.currentTimeMillis();
                ConfigBuilderPlugin.getPlugin().getCommandQueue().readStatus("KeepAlive. Status outdated.", null);
            } else if (isBasalOutdated && !pump.isBusy()) {
                lastReadStatus = System.currentTimeMillis();
                ConfigBuilderPlugin.getPlugin().getCommandQueue().readStatus("KeepAlive. Basal outdated.", null);
            }
        }
        if (lastRun != 0 && System.currentTimeMillis() - lastRun > T.mins(10).msecs()) {
            log.error("KeepAlive fail");
            FabricPrivacy.getInstance().logCustom(new CustomEvent("KeepAliveFail"));
        }
        lastRun = System.currentTimeMillis();
    }

    //called by MainApp at first app start
    public void setAlarm(Context context) {

        LocalAlertUtils.shortenSnoozeInterval();
        LocalAlertUtils.presnoozeAlarms();

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, KeepAliveReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, 0);
        try {
            pi.send();
        } catch (PendingIntent.CanceledException e) {
        }
        am.cancel(pi);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), Constants.keepAliveMsecs, pi);
    }

}

package info.nightscout.androidaps.plugins.general;

import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.events.EventRefreshGui;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.Careportal.Dialogs.NewNSTreatmentDialog;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.ConstraintsObjectives.ObjectivesPlugin;
import info.nightscout.androidaps.plugins.Food.FoodPlugin;
import info.nightscout.androidaps.plugins.IobCobCalculator.events.EventIobCalculationProgress;
import info.nightscout.androidaps.plugins.Loop.LoopPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.OpenAPSAMA.OpenAPSAMAPlugin;
import info.nightscout.androidaps.plugins.OpenAPSMA.OpenAPSMAPlugin;
import info.nightscout.androidaps.plugins.OpenAPSSMB.OpenAPSSMBPlugin;
import info.nightscout.androidaps.plugins.ProfileLocal.LocalProfilePlugin;
import info.nightscout.androidaps.plugins.ProfileNS.NSProfilePlugin;
import info.nightscout.androidaps.plugins.ProfileSimple.SimpleProfilePlugin;
import info.nightscout.androidaps.plugins.Source.BGSourceFragment;
import info.nightscout.androidaps.plugins.Source.SourceDexcomG5Plugin;
import info.nightscout.androidaps.plugins.Source.SourceGlimpPlugin;
import info.nightscout.androidaps.plugins.Source.SourceMM640gPlugin;
import info.nightscout.androidaps.plugins.Source.SourceNSClientPlugin;
import info.nightscout.androidaps.plugins.Source.SourcePoctechPlugin;
import info.nightscout.androidaps.plugins.Source.SourceXdripPlugin;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsPlugin;
import info.nightscout.androidaps.receivers.SourceFileReceiver;
import info.nightscout.androidaps.services.Intents;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.SP;
import info.nightscout.utils.T;

public class InSilicoStudyDataPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.DATABASE);

    private static InSilicoStudyDataPlugin plugin = null;

    private Context context;
    HandlerThread handlerThread;
    Handler handler;
    private static SourceFileReceiver sfReciever = new SourceFileReceiver();


    private final String ID_KEY = "InSilicoID";
    private boolean importUsed = false;

    public static InSilicoStudyDataPlugin getPlugin(Context context) {
        if (plugin == null)
            plugin = new InSilicoStudyDataPlugin(context);
        return plugin;
    }

    @Nullable
    public static InSilicoStudyDataPlugin getPlugin() {
        return plugin;
    }

    private InSilicoStudyDataPlugin(Context context) {
        super(new PluginDescription()
                .mainType(PluginType.BGSOURCE)
                .fragmentClass(BGSourceFragment.class.getName())
                .pluginName(R.string.SourceFile)
                .shortName(R.string.sourceFile_shortname)
                .preferencesId(R.xml.pref_sourcefile)
                .description(R.string.description_source_file)
        );

        this.context = context;

        handlerThread = new HandlerThread(InSilicoStudyDataPlugin.class.getName());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public void registerReceiver() {
        context.registerReceiver(sfReciever, new IntentFilter(Intents.ACTION_READ_SF), null, handler); // Will not run on main thread
    }

    public boolean inStudy() {
        return importUsed;
    }

    public void exec(String input, String output, Boolean forceClear) throws IOException {
        importUsed = true;
        if (forceClear)
            clearDatabase();
        configEnvironment();
        importFile(input);
    }

    private void configEnvironment() {
        NSClientPlugin.getPlugin().setPluginEnabled(PluginType.GENERAL, false);
        NSClientPlugin.getPlugin().setFragmentVisible(PluginType.GENERAL, false);

        MainApp.removePlugin(ObjectivesPlugin.getPlugin());

        LoopPlugin.getPlugin().setPluginEnabled(PluginType.LOOP, true);
        LoopPlugin.getPlugin().setFragmentVisible(PluginType.LOOP, true);
        SP.putString(R.string.key_aps_mode, "closed");

        OpenAPSMAPlugin.getPlugin().setPluginEnabled(PluginType.APS, false);
        OpenAPSAMAPlugin.getPlugin().setPluginEnabled(PluginType.APS, false);
        OpenAPSSMBPlugin.getPlugin().setPluginEnabled(PluginType.APS, true);
        OpenAPSSMBPlugin.getPlugin().setFragmentVisible(PluginType.APS, true);

        SourceDexcomG5Plugin.getPlugin().setPluginEnabled(PluginType.BGSOURCE, true);
        SourceGlimpPlugin.getPlugin().setPluginEnabled(PluginType.BGSOURCE, false);
        SourceMM640gPlugin.getPlugin().setPluginEnabled(PluginType.BGSOURCE, false);
        SourceNSClientPlugin.getPlugin().setPluginEnabled(PluginType.BGSOURCE, false);
        SourcePoctechPlugin.getPlugin().setPluginEnabled(PluginType.BGSOURCE, false);
        SourceXdripPlugin.getPlugin().setPluginEnabled(PluginType.BGSOURCE, false);

        LocalProfilePlugin.getPlugin().setPluginEnabled(PluginType.PROFILE, true);
        LocalProfilePlugin.getPlugin().setFragmentVisible(PluginType.PROFILE, true);
        NSProfilePlugin.getPlugin().setPluginEnabled(PluginType.PROFILE, false);
        SimpleProfilePlugin.getPlugin().setPluginEnabled(PluginType.PROFILE, false);

        // how to detect max values
        // is it possible from age, weight and sum of basal rates?

        // what sensitivity plugin ?

        ConfigBuilderPlugin.getPlugin().storeSettings("InSilico");
        MainApp.bus().post(new EventRefreshGui(true));
    }

    private void clearDatabase() {
        MainApp.getDbHelper().resetDatabases();
        // should be handled by Plugin-Interface and
        // additional service interface and plugin registry
        FoodPlugin.getPlugin().getService().resetFood();
        TreatmentsPlugin.getPlugin().getService().resetTreatments();
    }

    private boolean importFile(String input) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "imports");
        File importFile = new File(dir, input);

        InputStream is = new FileInputStream(importFile);

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;

        line = reader.readLine(); // ID: adult#001

        if (!SP.getString(ID_KEY, "").equals(line)) {
            // new person
            clearDatabase();
            SP.putString(ID_KEY, line);
        }

        log.debug("========== FILE ==========");

        MainApp.bus().post(new EventIobCalculationProgress("Loading Profile"));

        // read basal rates
        // 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54
        // 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54
        // 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54
        // 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54 1.54
        if (!readUpTo(reader, "Basal rate in 30min steps (U/h):")) return false;

        JSONArray basals = new JSONArray();
        line = reader.readLine();
        read12values(line, basals, 0);
        line = reader.readLine();
        read12values(line, basals, T.hours(6).secs());
        line = reader.readLine();
        read12values(line, basals, T.hours(12).secs());
        line = reader.readLine();
        read12values(line, basals, T.hours(18).secs());

        log.debug("Basals: " + basals.toString());

        // read ISF
        // 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56
        // 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56
        // 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56
        // 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56 1.56
        if (!readUpTo(reader, "Correction factor in 30min steps (mmol/L/U):")) return false;

        JSONArray isfs = new JSONArray();
        line = reader.readLine();
        read12values(line, isfs, 0);
        line = reader.readLine();
        read12values(line, isfs, T.hours(6).secs());
        line = reader.readLine();
        read12values(line, isfs, T.hours(12).secs());
        line = reader.readLine();
        read12values(line, isfs, T.hours(18).secs());

        log.debug("ISFs: " + isfs.toString());

        // read IC
        // 13.94 13.94 13.94 13.94 13.94 13.94 13.94 13.94 5.68 5.68 5.68 5.68
        // 5.68 5.68 5.68 5.68 5.68 5.68 5.68 5.68 5.68 5.68 6.46 6.46
        // 6.46 6.46 6.46 6.46 6.46 6.46 6.46 6.46 6.46 6.46 13.94 13.94
        // 13.94 13.94 13.94 13.94 13.94 13.94 13.94 13.94 13.94 13.94 13.94 13.94
        if (!readUpTo(reader, "CHO to insulin ratio in 30min steps (g-CHO/U):")) return false;

        JSONArray ics = new JSONArray();
        line = reader.readLine();
        read12values(line, ics, 0);
        line = reader.readLine();
        read12values(line, ics, T.hours(6).secs());
        line = reader.readLine();
        read12values(line, ics, T.hours(12).secs());
        line = reader.readLine();
        read12values(line, ics, T.hours(18).secs());

        log.debug("ICs: " + ics.toString());

        try {
            JSONObject json = new JSONObject();
            JSONObject store = new JSONObject();
            JSONObject jp = new JSONObject();
            json.put("defaultProfile", "InSilico");
            json.put("store", store);
            jp.put("units", "mmol");
            jp.put("dia", "5");
            jp.put("carbratio", ics);
            jp.put("sens", isfs);
            jp.put("basal", basals);
            jp.put("target_low", new JSONArray("[{\"time\":\"00:00\",\"value\":\"5.5\",\"timeAsSeconds\":\"0\"}]"));
            jp.put("target_high", new JSONArray("[{\"time\":\"00:00\",\"value\":\"5.5\",\"timeAsSeconds\":\"0\"}]"));
            store.put("InSilico", jp);

            ProfileStore profileStore = new ProfileStore(json);

            ProfileSwitch profileSwitch = NewNSTreatmentDialog.prepareProfileSwitch(profileStore, "InSilico", 0, 100, 0, 100000);
            profileSwitch.source = Source.NIGHTSCOUT;
            TreatmentsPlugin.getPlugin().addToHistoryProfileSwitch(profileSwitch);
            SystemClock.sleep(2000); // wait for processing
        } catch (JSONException e) {
            e.printStackTrace();
        }

        MainApp.bus().post(new EventIobCalculationProgress("Loading carbs"));

        // read meals
        // Enteral_bolus (meal) ********************************************
        // Time 	 	 	 	 	 CHO
        // (dd/mm/yyyy hh:mm) 	 	 (g)
        // 27/07/2018 08:00 	 	 60.00
        // 27/07/2018 13:00 	 	 60.00
        if (!readUpTo(reader, "Enteral_bolus (meal)")) return false;

        reader.readLine(); // skip header "Time CHO"
        reader.readLine(); // skip header "(dd/mm/yyyy hh:mm) 	 	 (g)"

        while ((line = reader.readLine()) != null && !line.equals("")) {
            InputEntry e = parseEntry(line);
            if (e != null) {
                DetailedBolusInfo t = new DetailedBolusInfo();
                t.date = e.date;
                t.carbs = e.value;
                t.source = Source.NIGHTSCOUT;
                t.notes = e.extra;
                if (t.date <= DateUtil.now())
                    TreatmentsPlugin.getPlugin().addToHistoryTreatment(t, true);
                else
                    log.warn("Ignoring CARBS: " + e.log());
            }
        }

        MainApp.bus().post(new EventIobCalculationProgress("Loading boluses"));

        // read bolus
        // Insulin_bolus ***************************************************
        // Time 	 	 	 	 	 Bolus
        // (dd/mm/yyyy hh:mm) 	 	 (U)
        // 27/07/2018 08:00 	 	 12.000000 R
        // 27/07/2018 08:05 	 	 0.405618 R
        // 27/07/2018 13:00 	 	 12.000000 R
        if (!readUpTo(reader, "Insulin_bolus")) return false;

        reader.readLine(); // skip header "Time Bolus"
        reader.readLine(); // skip header "(dd/mm/yyyy hh:mm) 	 	 (U)"

        while ((line = reader.readLine()) != null && !line.equals("")) {
            InputEntry e = parseEntry(line);
            if (e != null) {
                DetailedBolusInfo t = new DetailedBolusInfo();
                t.date = e.date + T.secs(1).msecs(); // to be sure it's different from carbs
                t.insulin = e.value;
                t.source = Source.NIGHTSCOUT;
                t.notes = e.extra;
                if (t.date <= DateUtil.now())
                    TreatmentsPlugin.getPlugin().addToHistoryTreatment(t, true);
                else
                    log.warn("Ignoring BOLUS: " + e.log());
            }
        }

        MainApp.bus().post(new EventIobCalculationProgress("Loading TBRs"));

        // read TBR
        // Insulin_infusion ***************************************************
        // Time 	 	 	 	 	 Basal rate
        // (dd/mm/yyyy hh:mm) 	 	 (U/h  S|R)
        // 28/07/2018 22:35 	 	 1.800000 R
        // 28/07/2018 22:40 	 	 1.800000 R
        // 28/07/2018 22:45 	 	 1.200000 R
        if (!readUpTo(reader, "Insulin_infusion")) return false;

        reader.readLine(); // skip header "Time Basal rate"
        reader.readLine(); // skip header "(dd/mm/yyyy hh:mm) 	 	 (U/h  S|R)"

        while ((line = reader.readLine()) != null && !line.equals("")) {
            InputEntry e = parseEntry(line);
            if (e != null) {
                if (e.date <= DateUtil.now() && ProfileFunctions.getInstance().getProfile().getBasal(e.date) != e.value) {
                    TemporaryBasal temporaryBasal = new TemporaryBasal()
                            .source(Source.NIGHTSCOUT)
                            .date(e.date)
                            .absolute(e.value)
                            .duration(5);
                    TreatmentsPlugin.getPlugin().addToHistoryTempBasal(temporaryBasal);
                } else {
                    log.warn("Ignoring TBR: " + e.log());
                }
            }
        }

        MainApp.bus().post(new EventIobCalculationProgress("Loading BGs"));

        // read CGM
        // Glucose_concentration ***************************************************
        // Time 	 	 	 	 	 conc
        // (dd/mm/yyyy hh:mm) 	 	 (mmol/L)
        // 28/07/2018 09:51 	 	 9.082348
        // 28/07/2018 09:52 	 	 9.058853
        // 28/07/2018 09:53 	 	 9.035480
        if (!readUpTo(reader, "Glucose_concentration")) return false;

        reader.readLine(); // skip header "Time conc"
        reader.readLine(); // skip header "(dd/mm/yyyy hh:mm) 	 	 (mmol/L)"

        while ((line = reader.readLine()) != null && !line.equals("")) {
            InputEntry e = parseEntry(line);
            if (e != null) {
                if (e.date <= DateUtil.now()) {
                    BgReading bgReading = new BgReading()
                            .date(e.date)
                            .value(e.value * Constants.MMOLL_TO_MGDL);
                    MainApp.getDbHelper().createIfNotExists(bgReading, "G5 Native");
                } else {
                    log.warn("Ignoring BG: " + e.log());
                }
            }
        }

        // read start time
        // Start ******************************************
        // Time (local time) 	 	 	 	 Bolus
        //        (dd/mm/yyyy hh:mm) 	 	 	 	 (Y/N)
        // 28/07/2018 22:50 	 	 		   N
        if (!readUpTo(reader, "Start")) return false;

        reader.readLine(); // skip header "Time (local time) 	 	 	 	 Bolus"
        reader.readLine(); // skip header "(dd/mm/yyyy hh:mm) 	 	 	 	 (Y/N)"

        line = reader.readLine();
        InputEntry start = parseDate(line);

        if (start.date <= DateUtil.now() - T.mins(2).msecs() || start.date > DateUtil.now()) {
            log.debug("Wrong start time: " + DateUtil.dateAndTimeFullString(start.date));
            //return false;
        }

        line = reader.readLine();
        if (!line.startsWith("END")) {
            log.debug("Missing END: ");
            return false;
        }

        MainApp.bus().post(new EventIobCalculationProgress("Data Loaded"));

        log.debug("========== FILE ==========");
        return true;
    }

    private boolean readUpTo(BufferedReader reader, String header) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !line.contains(header)) {
            log.debug("Skipping: " + line);
        }

        if (!line.contains(header)) return false;
        return true;
    }

    private void read12values(String line, JSONArray array, long startSeconds) {
        String pattern = "[0-9]*\\.?[0-9]+";
        Matcher m = Pattern.compile(pattern).matcher(line);

        try {
            while (m.find()) {
                JSONObject item = new JSONObject();
                item.put("timeAsSeconds", startSeconds);
                item.put("value", m.group());
                array.put(item);
                startSeconds += T.mins(30).secs();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    InputEntry parseEntry(String line) {
        String pattern = "(\\d+)\\/(\\d+)\\/(\\d+)\\s+(\\d+)\\:(\\d+)\\s+(\\d+)\\.(\\d+)\\s+(.*)"; // 27/07/2018 08:00 	 	 12.000000 R

        Matcher m = Pattern.compile(pattern).matcher(line);
        if (m.find()) {
            Calendar calendar = new GregorianCalendar();
            calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(1)));
            calendar.set(Calendar.MONTH, Integer.parseInt(m.group(2)) - 1);
            calendar.set(Calendar.YEAR, Integer.parseInt(m.group(3)));
            calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
            calendar.set(Calendar.MINUTE, Integer.parseInt(m.group(5)));
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            String v = m.group(6) + "." + m.group(7);

            InputEntry e = new InputEntry();
            e.date = calendar.getTimeInMillis();
            e.value = Double.parseDouble(v);
            e.extra = m.group(8);

            //log.debug(e.log());
            return e;
        }
        return null;
    }

    @Nullable
    InputEntry parseDate(String line) {
        String pattern = "(\\d+)\\/(\\d+)\\/(\\d+)\\s+(\\d+)\\:(\\d+)(.*)"; // 27/07/2018 08:00 	 	 N

        Matcher m = Pattern.compile(pattern).matcher(line);
        if (m.find()) {
            Calendar calendar = new GregorianCalendar();
            calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(1)));
            calendar.set(Calendar.MONTH, Integer.parseInt(m.group(2)) - 1);
            calendar.set(Calendar.YEAR, Integer.parseInt(m.group(3)));
            calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
            calendar.set(Calendar.MINUTE, Integer.parseInt(m.group(5)));
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            InputEntry e = new InputEntry();
            e.date = calendar.getTimeInMillis();

            //log.debug(e.log());
            return e;
        }
        return null;
    }

}

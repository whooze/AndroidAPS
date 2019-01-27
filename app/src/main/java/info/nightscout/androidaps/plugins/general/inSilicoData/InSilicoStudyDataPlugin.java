package info.nightscout.androidaps.plugins.general.inSilicoData;

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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.events.Event;
import info.nightscout.androidaps.events.EventNewBasalProfile;
import info.nightscout.androidaps.events.EventRefreshGui;
import info.nightscout.androidaps.events.EventRefreshOverview;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.Careportal.Dialogs.NewNSTreatmentDialog;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.ConstraintsObjectives.ObjectivesPlugin;
import info.nightscout.androidaps.plugins.IobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.IobCobCalculator.events.EventIobCalculationProgress;
import info.nightscout.androidaps.plugins.Loop.APSResult;
import info.nightscout.androidaps.plugins.Loop.LoopPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.OpenAPSAMA.OpenAPSAMAPlugin;
import info.nightscout.androidaps.plugins.OpenAPSMA.OpenAPSMAPlugin;
import info.nightscout.androidaps.plugins.OpenAPSSMB.OpenAPSSMBPlugin;
import info.nightscout.androidaps.plugins.ProfileLocal.LocalProfilePlugin;
import info.nightscout.androidaps.plugins.ProfileNS.NSProfilePlugin;
import info.nightscout.androidaps.plugins.ProfileSimple.SimpleProfilePlugin;
import info.nightscout.androidaps.plugins.PumpCombo.ComboPlugin;
import info.nightscout.androidaps.plugins.PumpDanaR.DanaRPlugin;
import info.nightscout.androidaps.plugins.PumpDanaRS.DanaRSPlugin;
import info.nightscout.androidaps.plugins.PumpDanaRv2.DanaRv2Plugin;
import info.nightscout.androidaps.plugins.PumpInsight.InsightPlugin;
import info.nightscout.androidaps.plugins.PumpVirtual.VirtualPumpPlugin;
import info.nightscout.androidaps.plugins.Sensitivity.SensitivityAAPSPlugin;
import info.nightscout.androidaps.plugins.Sensitivity.SensitivityOref0Plugin;
import info.nightscout.androidaps.plugins.Sensitivity.SensitivityOref1Plugin;
import info.nightscout.androidaps.plugins.Sensitivity.SensitivityWeightedAveragePlugin;
import info.nightscout.androidaps.plugins.Source.BGSourceFragment;
import info.nightscout.androidaps.plugins.Source.SourceDexcomG5Plugin;
import info.nightscout.androidaps.plugins.Source.SourceGlimpPlugin;
import info.nightscout.androidaps.plugins.Source.SourceMM640gPlugin;
import info.nightscout.androidaps.plugins.Source.SourceNSClientPlugin;
import info.nightscout.androidaps.plugins.Source.SourcePoctechPlugin;
import info.nightscout.androidaps.plugins.Source.SourceXdripPlugin;
import info.nightscout.androidaps.plugins.Treatments.Treatment;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsPlugin;
import info.nightscout.androidaps.receivers.SourceFileReceiver;
import info.nightscout.androidaps.services.Intents;
import info.nightscout.utils.BolusWizard;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.SP;
import info.nightscout.utils.T;

public class InSilicoStudyDataPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.DATABASE);

    private static InSilicoStudyDataPlugin plugin = null;

    // ********* CONSTANTS ***********

    private double target = 5.5d;

    //1)	AMA (temp basals only)
    //2)	SMB with full bolus issued in wizard
    //3)	SMB with ½ of full bolus in wizard
    //4)	No bolus, carbs announcement only
    //5)	No carbs announcements at all – full loop

    private int configuration = 1;


    private final double HYPO_TT_TARGET = 7.5;
    private final int HYPO_TT_DURATION = 60;

    private final double MAX_BASAL = 10.0;
    private final double MAX_IOB = 10.0;

    // ********* CONSTANTS ***********

    InputEntry start;
    long OFFSET;

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
                .mainType(PluginType.GENERAL)
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

    public void exec(String input, String output, int configuration, double target) throws IOException {
        log.debug("EXECUTING study data");
        importUsed = true;

        MainApp.bus().disablePost();

        clearDatabase();

        this.configuration = configuration;
        this.target = target;

        configEnvironment();
        importFile(input);

        MainApp.bus().post(new EventIobCalculationProgress("Processing data"));
        SystemClock.sleep(3000);

        MainApp.bus().post(new EventIobCalculationProgress("Running calculation"));
        OpenAPSSMBPlugin.getPlugin().invoke("InSilico", false);
        APSResult result = OpenAPSSMBPlugin.getPlugin().getLastAPSResult();
        if (result != null) {
            MainApp.bus().post(new EventIobCalculationProgress("Writing output file"));
            exportFile(output, result);
        }

        MainApp.bus().enablePost();
        MainApp.bus().post(new EventRefreshOverview("InSilico"));
    }

    private void configEnvironment() {
        Config.IGNORE_BASAL_ALLIGNMENT = true;

        SensitivityAAPSPlugin.getPlugin().setPluginEnabled(PluginType.SENSITIVITY, false);
        SensitivityWeightedAveragePlugin.getPlugin().setPluginEnabled(PluginType.SENSITIVITY, false);
        SensitivityOref1Plugin.getPlugin().setPluginEnabled(PluginType.SENSITIVITY, true);
        SensitivityOref1Plugin.getPlugin().setFragmentVisible(PluginType.SENSITIVITY, true);
        SensitivityOref0Plugin.getPlugin().setPluginEnabled(PluginType.SENSITIVITY, false);

        OpenAPSMAPlugin.getPlugin().setPluginEnabled(PluginType.APS, false);
        OpenAPSAMAPlugin.getPlugin().setPluginEnabled(PluginType.APS, false);
        OpenAPSSMBPlugin.getPlugin().setPluginEnabled(PluginType.APS, true);
        OpenAPSSMBPlugin.getPlugin().setFragmentVisible(PluginType.APS, true);

        switch (configuration) {
            case 1:
                SP.putBoolean(R.string.key_use_smb, false);
                break;
            case 2:
            case 3:
            case 4:
            case 5:
                SP.putBoolean(R.string.key_use_smb, true);
                break;
        }

        NSClientPlugin.getPlugin().setPluginEnabled(PluginType.GENERAL, false);
        NSClientPlugin.getPlugin().setFragmentVisible(PluginType.GENERAL, false);

        MainApp.removePlugin(ObjectivesPlugin.getPlugin());

        SP.putString(R.string.key_age, MainApp.gs(R.string.key_adult));

        LoopPlugin.getPlugin().setPluginEnabled(PluginType.LOOP, false);
        LoopPlugin.getPlugin().setFragmentVisible(PluginType.LOOP, false);
        SP.putString(R.string.key_aps_mode, "closed");
        SP.putString(R.string.key_loop_openmode_min_change, "0");

        SP.putDouble(R.string.key_openapsma_max_basal, MAX_BASAL);
        SP.putDouble(R.string.key_openapssmb_max_iob, MAX_IOB);
        SP.putBoolean(R.string.key_openapsama_useautosens, true);
        SP.putBoolean(R.string.key_enableSMB_with_COB, true);
        SP.putBoolean(R.string.key_enableSMB_with_temptarget, true);
        SP.putBoolean(R.string.key_allowSMB_with_high_temptarget, false);
        SP.putBoolean(R.string.key_enableSMB_always, true);
        SP.putBoolean(R.string.key_enableSMB_after_carbs, true);
        SP.putString(R.string.key_smbmaxminutes, "30");
        SP.putBoolean(R.string.key_use_uam, true);
        SP.putBoolean(R.string.key_high_temptarget_raises_sensitivity, true);
        SP.putBoolean(R.string.key_low_temptarget_lowers_sensitivity, true);
        SP.putString(R.string.key_openapsama_min_5m_carbimpact, "8");

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

        DanaRPlugin.getPlugin().setPluginEnabled(PluginType.PUMP, false);
        DanaRv2Plugin.getPlugin().setPluginEnabled(PluginType.PUMP, false);
        DanaRSPlugin.getPlugin().setPluginEnabled(PluginType.PUMP, false);
        ComboPlugin.getPlugin().setPluginEnabled(PluginType.PUMP, false);
        InsightPlugin.getPlugin().setPluginEnabled(PluginType.PUMP, false);
        VirtualPumpPlugin.getPlugin().setPluginEnabled(PluginType.PUMP, true);

        // how to detect max values
        // is it possible from age, weight and sum of basal rates?

        ConfigBuilderPlugin.getPlugin().storeSettings("InSilico");
        MainApp.bus().post(new EventRefreshGui(true));
    }

    private void clearDatabase() {
        //MainApp.getDbHelper().resetDatabases();
        // should be handled by Plugin-Interface and
        // additional service interface and plugin registry
        //FoodPlugin.getPlugin().getService().resetFood();
        //TreatmentsPlugin.getPlugin().getService().resetTreatments();
        TreatmentsPlugin.getPlugin().getTreatments().clear();
        TreatmentsPlugin.getPlugin().getTempBasals().clear();
        TreatmentsPlugin.getPlugin().getExtendedBoluses().clear();
        TreatmentsPlugin.getPlugin().getTempTargets().clear();
        TreatmentsPlugin.getPlugin().getProfiles().reset();
        IobCobCalculatorPlugin.getPlugin().initBgReadings();
        IobCobCalculatorPlugin.getPlugin().clearCache();
    }

    private boolean importFile(String input) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "imports");
        File importFile = new File(dir, input);

        InputStream is = new FileInputStream(importFile);

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;

        if (!readUpTo(reader, "Start")) return false;
        reader.readLine(); // skip header "Time (local time) 	 	 	 	 Bolus"
        reader.readLine(); // skip header "(dd/mm/yyyy hh:mm) 	 	 	 	 (Y/N)"
        line = reader.readLine();
        start = parseDate(line);
        OFFSET = DateUtil.now() - start.date;
        int PROFILESHIFT = (int) ((DateUtil.keepTimeOnly(DateUtil.now()) - DateUtil.keepTimeOnly(start.date)) / 1000); //from msecs to secs
        log.debug("AGO set to " + DateUtil.minAgo(start.date));

        // rewind back to start of the file
        ((FileInputStream) is).getChannel().position(0);
        reader = new BufferedReader(new InputStreamReader(is));

        line = reader.readLine(); // ID: adult#001
        if (!SP.getString(ID_KEY, "").equals(line)) {
            // new person
            clearDatabase();
            SP.putString(ID_KEY, line);
        }

        log.debug("========== FILE ==========");

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
            jp.put("target_low", new JSONArray("[{\"time\":\"00:00\",\"value\":\"" + target + "\",\"timeAsSeconds\":\"0\"}]"));
            jp.put("target_high", new JSONArray("[{\"time\":\"00:00\",\"value\":\"" + target + "\",\"timeAsSeconds\":\"0\"}]"));
            store.put("InSilico", jp);

            ProfileStore profileStore = new ProfileStore(json);

            ProfileSwitch profileSwitch = NewNSTreatmentDialog.prepareProfileSwitch(profileStore, "InSilico", 0, 100, PROFILESHIFT, 100000);
            profileSwitch.source = Source.NIGHTSCOUT;
            TreatmentsPlugin.getPlugin().getProfiles().add(profileSwitch);
        } catch (JSONException e) {
            e.printStackTrace();
        }

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
                Treatment t = new Treatment();
                t.date = e.date + OFFSET;
                t.carbs = e.value;
                t.source = Source.NIGHTSCOUT;
                TreatmentsPlugin.getPlugin().getTreatments().add(t);

                // Start hypo target if needed
                BgReading actual = IobCobCalculatorPlugin.getPlugin().findOlder(t.date);
                if (actual != null && actual.value < 72) {
                    TempTarget tempTarget = new TempTarget()
                            .date(t.date)
                            .duration(HYPO_TT_DURATION)
                            .reason(MainApp.gs(R.string.hypo))
                            .source(Source.USER)
                            .low(Profile.toMgdl(HYPO_TT_TARGET, Constants.MMOL))
                            .high(Profile.toMgdl(HYPO_TT_TARGET, Constants.MMOL));
                    if (TreatmentsPlugin.getPlugin().getTempTargetFromHistory(t.date) == null)
                        TreatmentsPlugin.getPlugin().getTempTargets().add(tempTarget);
                }
            }
        }

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
                Treatment t = new Treatment();
                t.date = e.date + T.secs(1).msecs() + OFFSET; // to be sure it's different from carbs
                t.insulin = e.value;
                t.source = Source.NIGHTSCOUT;
                TreatmentsPlugin.getPlugin().getTreatments().add(t);
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
                if (ProfileFunctions.getInstance().getProfile().getBasal(e.date + OFFSET) != e.value) {
                    TemporaryBasal temporaryBasal = new TemporaryBasal()
                            .source(Source.NIGHTSCOUT)
                            .date(e.date + OFFSET)
                            .absolute(e.value)
                            .duration(5);
                    TreatmentsPlugin.getPlugin().getTempBasals().add(temporaryBasal);
                } else {
                    log.warn("Ignoring TBR: " + e.log());
                }
            }
        }

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

        long last_date = 0;

        while ((line = reader.readLine()) != null && !line.equals("")) {
            InputEntry e = parseEntry(line);
            if (e != null) {
                if ((start.date - e.date) % T.mins(5).msecs() < 1000 ) {
                    log.debug(DateUtil.dateAndTimeFullString(e.date) + " -> " + DateUtil.dateAndTimeFullString(e.date + OFFSET));
                    BgReading bgReading = new BgReading()
                            .date(e.date + OFFSET)
                            .value(e.value * Constants.MMOLL_TO_MGDL);
                    if (bgReading.value < 39) bgReading.value = 39;
                    IobCobCalculatorPlugin.getPlugin().getBgReadings().add(0, bgReading);
                    last_date = e.date;
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
        start = parseDate(line);

        line = reader.readLine();
        if (!line.startsWith("END")) {
            log.debug("Missing END: ");
            return false;
        }

        IobCobCalculatorPlugin.getPlugin().createBucketedData();
        IobCobCalculatorPlugin.getPlugin().runCalculation("inSilico", System.currentTimeMillis(), false, true, new EventNewBasalProfile());
        IobCobCalculatorPlugin.getPlugin().waitForCalculation();

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

    private boolean exportFile(String output, APSResult result) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "imports");
        File outputFile = new File(dir, output);
        OutputStream os = new FileOutputStream(outputFile);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));

        double rate = result.rate;
        double bolus = result.smb;

        Profile profile = ProfileFunctions.getInstance().getProfile(result.date);

        if (result.rate == 0 && result.duration == 0) {
            rate = profile.getBasal();
        }

        MealData mealData = TreatmentsPlugin.getPlugin().getMealData();

        BolusWizard wizard = null;
        if (Math.abs(mealData.lastCarbTime - DateUtil.now()) < T.mins(4).msecs()) {
            // carbs issued now
            if (Math.abs(mealData.lastBolusTime - DateUtil.now()) > T.mins(1).msecs()) {
                // but bolus was not given => run wizard

                BgReading lastBg = DatabaseHelper.actualBg();

                wizard = new BolusWizard();
                wizard.doCalc(profile,
                        null,
                        (int) mealData.lastCarbsAmount,
                        0d, //cob,
                        lastBg.valueToUnits(Constants.MMOL),
                        0d,
                        false,
                        false,
                        false,
                        true
                );
                bolus = wizard.calculatedTotalInsulin;
                switch (configuration) {
                    case 1:
                    case 2:
                        // full bolus
                        break;
                    case 3:
                        bolus = bolus / 2;
                        break;
                    case 4:
                    case 5:
                        bolus = 0;
                        break;
                }
            }
        }

        writer.write(SP.getString(ID_KEY, "ID: unknown"));
        writer.newLine();
        writer.newLine();
        writer.write("(dd/mm/yyyy  hh:mm)      (U/h)      (U)   \"Diagnostics\"  \"Large change Y/N\"   ");
        writer.newLine();

        if (result.isChangeRequested()) {
            Calendar calendar = new GregorianCalendar();
            writer.write(String.format("%02d/%02d/%02d %02d:%02d  \t \t %.3f  \t %.2f \t\t \"SSM=no\"        \" \" ",
                    calendar.get(Calendar.DAY_OF_MONTH),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    rate,
                    bolus
            ));
        }
        //writer.write("29/07/18 00:00  \t \t 2.673857  \t 0.000000 \t\t \"SSM=no\"        \" \" ");
        writer.newLine();
        writer.newLine();
        writer.write("END");
        writer.newLine();

        writer.write(result.json().toString());
        if (wizard != null)
            writer.write(wizard.log());

        writer.flush();
        return true;
    }

}

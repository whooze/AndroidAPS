package info.nightscout.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.text.format.DateFormat;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.data.NonOverlappingIntervals;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.TDD;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.Treatments.Treatment;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsPlugin;

/**
 * Save the SQL database to file.
 */
public class DataExporter {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Logger log = LoggerFactory.getLogger(L.DATABASE);

    private static void toastText(final Context context, final String text) {
        handler.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    /**
     * Generate a csv that can be imported by SiDiary
     */
    public static String saveCSV(Context context, long from) {


        int permissionCheck = ContextCompat.checkSelfPermission(MainApp.instance(),
                Manifest.permission.READ_EXTERNAL_STORAGE);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        FileOutputStream foStream;
        PrintStream printStream = null;
        ZipOutputStream zipOutputStream = null;
        String zipFilename = null;
        try {

            final String dir = getExternalDir();
            if (!makeSureDirectoryExists(dir)) {
                log.debug("Directory does not exist.");
                return null;
            }

            final StringBuilder sb = new StringBuilder();
            sb.append(dir);
            sb.append("/exportCSV");
            sb.append(DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()));
            sb.append(".zip");
            zipFilename = sb.toString();
            final File sd = Environment.getExternalStorageDirectory();
            if (sd.canWrite()) {
                final File zipOutputFile = new File(zipFilename);

                foStream = new FileOutputStream(zipOutputFile);
                zipOutputStream = new ZipOutputStream(new BufferedOutputStream(foStream));
                zipOutputStream.putNextEntry(new ZipEntry("tdds-" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                printStream = new PrintStream(zipOutputStream);

                //add TreatmentsPlugint and BGlucose Header
                printStream.println("DAY;TIME;TDD;BASAL;BOLUS;PROFILE_BASAL");
                java.text.DateFormat df = new SimpleDateFormat("dd.MM.yyyy;HH:mm;");
                List<TDD> historyList = MainApp.getDbHelper().getTDDs(365);
                Date date = new Date();
                Date dateEnd = new Date();
                for (TDD tdd : historyList
                        ) {
                    if (tdd.date >= from) {
                        date.setTime(tdd.date);
                        ProfileSwitch profileSwitch = TreatmentsPlugin.getPlugin().getProfileSwitchFromHistory(tdd.date);
                        printStream.println(df.format(date) + DecimalFormatter.to2Decimal(tdd.getTotal()) + ";" + DecimalFormatter.to2Decimal(tdd.basal) + ";" + DecimalFormatter.to2Decimal(tdd.bolus) + ";" + DecimalFormatter.to2Decimal(profileSwitch.getProfileObject().percentageBasalSum()));
                    }
                }
                printStream.flush();
                historyList.clear();
                zipOutputStream.putNextEntry(new ZipEntry("treatments-" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                List<Treatment> treatmentList = TreatmentsPlugin.getPlugin().getService().getTreatmentDataFromTime(from, false);
                printStream.println("DAY;TIME;CARBS;BOLUS;VALID");

                for (Treatment treat : treatmentList
                        ) {
                    if (!treat.isSMB) {
                        date.setTime(treat.date);
                        printStream.println(df.format(date) + DecimalFormatter.to0Decimal(treat.carbs) + ";" +  DecimalFormatter.to2Decimal(treat.insulin) +";" + treat.isValid );
                    }
                }
                printStream.flush();
                zipOutputStream.putNextEntry(new ZipEntry("automatic-smb-" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                printStream.println("DAY;TIME;BOLUS;VALID");

                for (Treatment treat : treatmentList
                        ) {
                    if (treat.isSMB) {
                        date.setTime(treat.date);
                        printStream.println(df.format(date)  +  DecimalFormatter.to2Decimal(treat.insulin) +";" + treat.isValid );
                    }
                }
                printStream.flush();
                treatmentList.clear();

                zipOutputStream.putNextEntry(new ZipEntry("extended-bolus-" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                NonOverlappingIntervals<ExtendedBolus> extendedBoluses = new NonOverlappingIntervals<>();
                extendedBoluses.reset().add(MainApp.getDbHelper().getExtendedBolusDataFromTime(from, false));

                printStream.println("DAY_START;TIME_START;DAY_END;TIME_END;BOLUS;DURATION-PROGRAMMED;VALID");
                for (int i = 0; i < extendedBoluses.size(); i++) {
                        ExtendedBolus eb = extendedBoluses.get(i);
                        date.setTime(eb.date);
                        dateEnd.setTime(eb.end());
                        printStream.println(df.format(date) + df.format(dateEnd)  +   DecimalFormatter.to2Decimal(eb.insulin) +";" + DecimalFormatter.to2Decimal(eb.durationInMinutes) +";" + eb.isValid );
                }
                printStream.flush();
                extendedBoluses.reset();


                zipOutputStream.putNextEntry(new ZipEntry("tbr-" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                NonOverlappingIntervals<TemporaryBasal> tempBasals = new NonOverlappingIntervals<>();
                tempBasals.reset().add(MainApp.getDbHelper().getTemporaryBasalsDataFromTime(from, false));

                printStream.println("DAY_START;TIME_START;DAY_END;TIME_END;PERCENTAGE;ABSOLUTE;IS_ABSOLUTE;VALID");
                for (int i = 0; i < tempBasals.size(); i++) {
                    TemporaryBasal tb = tempBasals.get(i);
                    date.setTime(tb.date);
                    dateEnd.setTime(tb.end());
                    printStream.println(df.format(date)  +  df.format(dateEnd)  +  DecimalFormatter.to0Decimal(tb.percentRate) +";" + DecimalFormatter.to2Decimal(tb.absoluteRate) +";" + tb.isAbsolute +";" + tb.isValid );
                }
                printStream.flush();
                tempBasals.reset();

                zipOutputStream.putNextEntry(new ZipEntry("glycemia-" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                List<BgReading> bgReadings = MainApp.getDbHelper().getAllBgreadingsDataFromTime(from, false);
                printStream.println("DAY;TIME;SENSOR_GLUCOSE");

                for (BgReading reading : bgReadings
                        ) {
                        date.setTime(reading.date);
                        printStream.println(df.format(date) + DecimalFormatter.to1Decimal(reading.value));
                }
                printStream.flush();
            } else {
                toastText(context, "SD card not writable!");
                log.debug("SD card not writable!");
            }

        } catch (IOException e) {
            toastText(context, "SD card not writable!");
            log.error("Exception while writing DB", e);
        } finally {
            if (printStream != null) {
                printStream.close();
            }
            if (zipOutputStream != null) try {
                zipOutputStream.close();
            } catch (IOException e1) {
                log.error("Something went wrong closing: ", e1);
            }
        }
        return zipFilename;
    }

    private static boolean makeSureDirectoryExists(String dir) {
        final File file = new File(dir);
        return file.exists() || file.mkdirs();
    }

    private static String getExternalDir() {
        final StringBuilder sb = new StringBuilder();
        sb.append(Environment.getExternalStorageDirectory().getAbsolutePath());
        sb.append("/aapsexport");
        final String dir = sb.toString();
        return dir;
    }
}

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
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.TDD;
import info.nightscout.androidaps.logging.L;
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
                zipOutputStream.putNextEntry(new ZipEntry("export" + DateFormat.format("yyyyMMdd-kkmmss", System.currentTimeMillis()) + ".csv"));
                printStream = new PrintStream(zipOutputStream);

                //add TreatmentsPlugint and BGlucose Header
                printStream.println("DAY;TIME;TDD;BASAL;BOLUS;PROFILE_BASAL");
                java.text.DateFormat df = new SimpleDateFormat("dd.MM.yyyy;HH:mm;");
                List<TDD> historyList = MainApp.getDbHelper().getTDDs(365);
                Date date = new Date();

                for (TDD tdd : historyList
                        ) {
                    if (tdd.date >= from) {
                        date.setTime(tdd.date);
                        ProfileSwitch profileSwitch = TreatmentsPlugin.getPlugin().getProfileSwitchFromHistory(tdd.date);
                        printStream.println(df.format(date) + Math.round(tdd.getTotal()) + ";" + Math.round(tdd.basal) + ";" + Math.round(tdd.bolus) + ";" + DecimalFormatter.to2Decimal(profileSwitch.getProfileObject().percentageBasalSum()));
                    }
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

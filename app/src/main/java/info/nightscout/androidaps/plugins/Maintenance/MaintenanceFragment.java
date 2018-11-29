package info.nightscout.androidaps.plugins.Maintenance;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.Toast;

import java.io.File;
import java.util.Calendar;
import java.util.GregorianCalendar;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.plugins.Food.FoodPlugin;
import info.nightscout.androidaps.plugins.Maintenance.activities.LogSettingActivity;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsPlugin;
import info.nightscout.utils.DataExporter;

/**
 *
 */
public class MaintenanceFragment extends Fragment {

    private Fragment f;

    @Override
    public void onResume() {
        super.onResume();

        this.f = this;
    }

    @Override
    public void onPause() {
        super.onPause();

        this.f = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.maintenance_fragment, container, false);

        final Fragment f = this;

        view.findViewById(R.id.log_send).setOnClickListener(view1 -> MaintenancePlugin.getPlugin().sendLogs());

        view.findViewById(R.id.log_delete).setOnClickListener(view1 -> MaintenancePlugin.getPlugin().deleteLogs());

        if(Config.POZNANSTUDY) {
            view.findViewById(R.id.nav_resetdb).setVisibility(View.GONE);
            view.findViewById(R.id.nav_exportstudydata).setVisibility(View.VISIBLE);
            view.findViewById(R.id.nav_exportstudydata).setOnClickListener(new View.OnClickListener() {
                                                                               @Override
                                                                               public void onClick(View v) {

                                                                                   int permissionCheck = ContextCompat.checkSelfPermission(MaintenanceFragment.this.getActivity(),
                                                                                           Manifest.permission.READ_EXTERNAL_STORAGE);
                                                                                   if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                                                                                       ActivityCompat.requestPermissions(MaintenanceFragment.this.getActivity(),
                                                                                               new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                                                                                               0);
                                                                                       Toast.makeText(MainApp.instance(), "Please retry after granting storage permissions!", Toast.LENGTH_LONG).show();
                                                                                       return;
                                                                                   }

                                                                                   final GregorianCalendar date = new GregorianCalendar();
                                                                                   //TODO: set previously stored date?
                                                                                   Dialog dialog = new DatePickerDialog(MaintenanceFragment.this.getContext(), new DatePickerDialog.OnDateSetListener() {
                                                                                       @SuppressLint("StaticFieldLeak")
                                                                                       @Override
                                                                                       public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                                                                                           date.set(year, monthOfYear, dayOfMonth);
                                                                                           date.set(Calendar.HOUR_OF_DAY, 0);
                                                                                           date.set(Calendar.MINUTE, 0);
                                                                                           date.set(Calendar.SECOND, 0);
                                                                                           date.set(Calendar.MILLISECOND, 0);

                                                                                           new AsyncTask<Void, Void, String>() {
                                                                                               @Override
                                                                                               protected String doInBackground(Void... params) {

                                                                                                   return DataExporter.saveCSV(MainApp.instance(), date.getTimeInMillis());
                                                                                               }

                                                                                               @Override
                                                                                               protected void onPostExecute(String filename) {
                                                                                                   super.onPostExecute(filename);
                                                                                                   if (filename != null) {
                                                                                                       //TODO: store date?
                                                                                                       Toast.makeText(MainApp.instance(), "Exported to: " + filename, Toast.LENGTH_LONG).show();
                                                                                                   } else {
                                                                                                       Toast.makeText(MainApp.instance(), "Could not export CSV :(", Toast.LENGTH_LONG).show();
                                                                                                   }
                                                                                               }
                                                                                           }.execute();
                                                                                       }
                                                                                   }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH));
                                                                                   dialog.show();

                                                                               }
                                                                           });
            //TODO poznanstudy: add functionallity to export button
        } else {
            view.findViewById(R.id.nav_resetdb).setOnClickListener(view1 -> new AlertDialog.Builder(f.getContext())
                    .setTitle(R.string.nav_resetdb)
                    .setMessage(R.string.reset_db_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        MainApp.getDbHelper().resetDatabases();
                        // should be handled by Plugin-Interface and
                        // additional service interface and plugin registry
                        FoodPlugin.getPlugin().getService().resetFood();
                        TreatmentsPlugin.getPlugin().getService().resetTreatments();
                    })
                    .create()
                    .show());
        }

        view.findViewById(R.id.nav_export).setOnClickListener(view1 -> {
            // start activity for checking permissions...
            ImportExportPrefs.verifyStoragePermissions(f);
            ImportExportPrefs.exportSharedPreferences(f);
        });

        view.findViewById(R.id.nav_import).setOnClickListener(view1 -> {
            // start activity for checking permissions...
            ImportExportPrefs.verifyStoragePermissions(f);
            ImportExportPrefs.importSharedPreferences(f);
        });

        view.findViewById(R.id.nav_logsettings).setOnClickListener(view1 -> {
            startActivity(new Intent(getActivity(), LogSettingActivity.class));
        });


        return view;
    }

}

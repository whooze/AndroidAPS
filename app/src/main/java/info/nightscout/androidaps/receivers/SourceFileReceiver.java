package info.nightscout.androidaps.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.Source.SourceFilePlugin;

public class SourceFileReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // start emulator in Studio (via tools -> avd manager)
        //  adb push testfile.json /storage/emulated/0/Android/data/info.nightscout.androidaps/files/imports/testfile.json
        // adb shell am broadcast -a org.nightscout.androidaps.ACTION_READ_SF
        Boolean param = true;
        System.out.println("=============================");
        System.out.println("Statting with : " + param.toString());
        System.out.println("=============================");

        if (param) {
            SourceFilePlugin plugin = MainApp.getSpecificPlugin(SourceFilePlugin.class);
            try {
                plugin.readDataFromFile();
            } catch (IOException e) {
                // this should be handled gracefully....
                e.printStackTrace();
            }
        }

    }

}

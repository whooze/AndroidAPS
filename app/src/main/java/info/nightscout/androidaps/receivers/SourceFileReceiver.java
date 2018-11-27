package info.nightscout.androidaps.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.general.InSilicoStudyDataPlugin;

public class SourceFileReceiver extends BroadcastReceiver {

    String input = "input";
    String output = "output";
    boolean clear = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        // start emulator in Studio (via tools -> avd manager)
        //  adb push testfile.json /storage/emulated/0/Android/data/info.nightscout.androidaps/files/imports/testfile.json
        // adb shell am broadcast -a org.nightscout.androidaps.ACTION_READ_SF
        if (intent.hasExtra("input"))
            input = intent.getStringExtra("input");
        if (intent.hasExtra("output"))
            output = intent.getStringExtra("output");
        if (intent.hasExtra("clear"))
            clear = Boolean.parseBoolean(intent.getStringExtra("clear"));

        System.out.println("=============================");
        System.out.println("Starting with : " + input + " " + output);
        System.out.println("=============================");

        try {
            InSilicoStudyDataPlugin.getPlugin().exec(input, output, clear);
        } catch (IOException e) {
            // this should be handled gracefully....
            e.printStackTrace();
        }

    }

}

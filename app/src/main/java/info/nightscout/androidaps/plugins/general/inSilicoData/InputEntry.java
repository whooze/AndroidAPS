package info.nightscout.androidaps.plugins.general.inSilicoData;

import info.nightscout.utils.DateUtil;

public class InputEntry {
    long date;
    double value;
    String extra = "";

    public String log() {
        return "InputEntry: " + DateUtil.dateAndTimeFullString(date) + "; value=" + value + "; extra=" + extra;
    }
}

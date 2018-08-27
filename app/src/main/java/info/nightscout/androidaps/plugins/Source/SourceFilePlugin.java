package info.nightscout.androidaps.plugins.Source;

import android.content.Context;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import info.nightscout.androidaps.R;
import info.nightscout.androidaps.interfaces.BgSourceInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;

public class SourceFilePlugin extends PluginBase implements BgSourceInterface {

    private static Logger log = LoggerFactory.getLogger(L.BGSOURCE);

    private static SourceFilePlugin plugin = null;

    private Context context;

    public static SourceFilePlugin getPlugin(Context context) {
        if (plugin == null)
            plugin = new SourceFilePlugin(context);
        return plugin;
    }

    private SourceFilePlugin(Context context) {
        super(new PluginDescription()
                .mainType(PluginType.BGSOURCE)
                .fragmentClass(BGSourceFragment.class.getName())
                .pluginName(R.string.SourceFile)
                .shortName(R.string.sourceFile_shortname)
                .preferencesId(R.xml.pref_sourcefile)
                .description(R.string.description_source_file)
        );

        this.context = context;
    }


    @Override
    public boolean advancedFilteringSupported() {
        return true;
    }

    @Override
    public void handleNewData(Intent intent) {
        // not necessary?
    }

    public void readDataFromFile() throws IOException {
        int resId = R.raw.testfile;
        InputStream is = this.context.getResources().openRawResource(resId);

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;


        log.debug("========== FILE ==========");
       while((line  = reader.readLine())  !=  null)  {
            log.debug("a new line: " + line);
        }
        log.debug("========== FILE ==========");
    }

}

package info.nightscout.androidaps.plugins.general;

import android.content.Context;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import info.nightscout.androidaps.R;
import info.nightscout.androidaps.interfaces.BgSourceInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.Source.BGSourceFragment;

public class InSilicoStudyDataPlugin extends PluginBase implements BgSourceInterface {

    private static Logger log = LoggerFactory.getLogger(L.BGSOURCE);

    private static InSilicoStudyDataPlugin plugin = null;

    private Context context;

    public static InSilicoStudyDataPlugin getPlugin(Context context) {
        if (plugin == null)
            plugin = new InSilicoStudyDataPlugin(context);
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
    }


    @Override
    public boolean advancedFilteringSupported() {
        return true;
    }

    @Override
    public void handleNewData(Intent intent) {
        // not necessary?
    }

    public void exec(String input, String output) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "imports");
        File importFile = new File(dir, input);

        InputStream is = new FileInputStream(importFile);

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;


        log.debug("========== FILE ==========");
       while((line  = reader.readLine())  !=  null)  {
            log.debug("a new line: " + line);
        }
        log.debug("========== FILE ==========");
    }

}

package sh.tuxbox.root;

import android.app.Application;

import com.topjohnwu.superuser.Shell;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30));
    }
}
package sh.tuxbox.root.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies the bundled helper binaries (assets/bin/* and assets/ptyd/<abi>/ptyd)
 * into the app's files dir and makes them executable.
 *
 * Concurrency-safe: every extraction writes to a temp file and atomically
 * renames it over the target, so a tab that execs ptyd while another tab is
 * re-extracting never sees a half-written or busy binary (ETXTBSY). install()
 * is also serialized so parallel tab opens don't fight over the same files.
 */
public final class AssetInstaller {

    private AssetInstaller() {}

    /** Serializes install() calls coming from concurrent terminal tabs. */
    private static final Object INSTALL_LOCK = new Object();

    /** ABI directory used to pick the right ptyd binary, e.g. "arm64-v8a". */
    public static String abiDir() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a":
                case "armeabi-v7a":
                case "x86_64":
                    return abi;
                default:
                    break;
            }
        }
        return null; // unsupported
    }

    public static File binDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "bin");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File ptydPath(Context ctx) {
        return new File(binDir(ctx), "ptyd");
    }

    /** Extract ptyd (matching device ABI) plus the three shell scripts into binDir. */
    public static void install(Context ctx) throws Exception {
        String abi = abiDir();
        if (abi == null) throw new IllegalStateException("ABI não suportada: " + String.join(",", android.os.Build.SUPPORTED_ABIS));

        synchronized (INSTALL_LOCK) {
            File binDir = binDir(ctx);
            installAtomically(ctx, "bin/tuxbox", new File(binDir, "tuxbox"));
            installAtomically(ctx, "bin/tuxbox-mini", new File(binDir, "tuxbox-mini"));
            installAtomically(ctx, "bin/getroot", new File(binDir, "getroot"));
            installAtomically(ctx, "bin/ensure-xkb", new File(binDir, "ensure-xkb"));
            installAtomically(ctx, "ptyd/" + abi + "/ptyd", ptydPath(ctx));
        }
    }

    /** Copy an asset to a temp file, make it executable, then atomically rename
     *  it over the target so the live file is never open for writing while a
     *  concurrent tab execs it. */
    private static void installAtomically(Context ctx, String asset, File dst) throws Exception {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try {
            copyAsset(ctx, asset, tmp);
            tmp.setExecutable(true, false);
            if (!tmp.renameTo(dst)) {
                // rename fallback (should not happen: same directory/filesystem)
                copyAsset(ctx, asset, dst);
                dst.setExecutable(true, false);
            }
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void copyAsset(Context ctx, String asset, File dst) throws Exception {
        try (InputStream in = ctx.getAssets().open(asset);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Return whether the ptyd binary is already extracted and executable. */
    public static boolean isInstalled(Context ctx) {
        File ptyd = ptydPath(ctx);
        return ptyd.exists() && ptyd.canExecute();
    }
}
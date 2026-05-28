package com.origin.launcher.Launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.widget.Toast;

import com.origin.launcher.versions.GameVersion;
import com.origin.launcher.utils.FeatureSettings;
import com.origin.launcher.dialogs.LoadingDialog;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MinecraftLauncher {
    private static final String TAG = "MinecraftLauncher";
    private final Context context;
    private GamePackageManager gameManager;
    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";
    private LoadingDialog loadingDialog;

    // ─── Version thresholds ───────────────────────────────────────────────────
    // libmaesdk.so  — required since 1.21.80 stable / 1.21.80.20 beta
    private static final String MAESDK_STABLE = "1.21.80";
    private static final String MAESDK_BETA   = "1.21.80.20";

    // libHttpClient.Android.so + libPlayFabMultiplayer.so — required since 1.21.130
    private static final String HTTPCLIENT_STABLE = "1.21.130";
    private static final String HTTPCLIENT_BETA   = "1.21.130.20";

    // Extra system libs that may be needed from 1.24.x onward
    private static final String NEW_LIBS_STABLE = "1.24.0";
    private static final String NEW_LIBS_BETA   = "1.24.0.20";
    // ─────────────────────────────────────────────────────────────────────────

    public MinecraftLauncher(Context context) {
        this.context = context;
    }

    public static String abiToSystemLibDir(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        return abi;
    }

    public ApplicationInfo createFakeApplicationInfo(GameVersion version, String packageName) {
        ApplicationInfo fakeInfo = new ApplicationInfo();
        File apkFile = new File(version.versionDir, "base.apk.xelo");
        fakeInfo.sourceDir = apkFile.getAbsolutePath();
        fakeInfo.publicSourceDir = fakeInfo.sourceDir;
        String systemAbi = abiToSystemLibDir(Build.SUPPORTED_ABIS[0]);
        File dstLibDir = new File(context.getDataDir(),
                "minecraft/" + version.directoryName + "/lib/" + systemAbi);
        fakeInfo.nativeLibraryDir = dstLibDir.getAbsolutePath();
        fakeInfo.packageName = packageName;
        fakeInfo.dataDir = version.versionDir.getAbsolutePath();

        File splitsFolder = new File(version.versionDir, "splits");
        if (splitsFolder.exists() && splitsFolder.isDirectory()) {
            File[] splits = splitsFolder.listFiles();
            if (splits != null) {
                ArrayList<String> splitPathList = new ArrayList<>();
                for (File f : splits) {
                    if (f.isFile() && f.getName().endsWith(".apk.xelo")) {
                        splitPathList.add(f.getAbsolutePath());
                    }
                }
                if (!splitPathList.isEmpty()) {
                    fakeInfo.splitSourceDirs = splitPathList.toArray(new String[0]);
                }
            }
        }
        return fakeInfo;
    }

    public void launch(Intent sourceIntent, GameVersion version) {
        Activity activity = (Activity) context;
        try {
            if (version == null) {
                Log.e(TAG, "No version selected");
                showLaunchErrorOnUi("No version selected");
                return;
            }
            Log.i(TAG, "Launching Minecraft version: " + version.versionCode);
            activity.runOnUiThread(() -> {
                dismissLoading();
                loadingDialog = new LoadingDialog(activity);
                loadingDialog.show();
            });
            new Thread(() -> {
                try {
                    gameManager = GamePackageManager.Companion.getInstance(
                            context.getApplicationContext(), version);
                    fillIntentWithMcPath(sourceIntent, version);
                    launchMinecraftActivity(sourceIntent, version, false);
                } catch (Exception e) {
                    Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                    activity.runOnUiThread(() -> {
                        dismissLoading();
                        showLaunchErrorOnUi("Launch failed: " + e.getMessage());
                    });
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Launch failed: " + e.getMessage(), e);
            dismissLoading();
            showLaunchErrorOnUi("Launch failed: " + e.getMessage());
        }
    }

    private void fillIntentWithMcPath(Intent sourceIntent, GameVersion version) {
        if (FeatureSettings.getInstance().isVersionIsolationEnabled()) {
            sourceIntent.putExtra("MC_PATH", version.versionDir.getAbsolutePath());
            sourceIntent.putExtra("IS_INSTALLED", version.isInstalled);
        } else {
            sourceIntent.putExtra("MC_PATH", "");
            sourceIntent.putExtra("IS_INSTALLED", false);
        }
    }

    private void launchMinecraftActivity(Intent sourceIntent, GameVersion version, boolean modsEnabled) {
        Activity activity = (Activity) context;
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    sourceIntent.putExtra("DISABLE_SPLASH_SCREEN", true);
                }

                sourceIntent.setClass(context, MinecraftActivity.class);
                ApplicationInfo mcInfo = version.isInstalled
                        ? gameManager.getPackageContext().getApplicationInfo()
                        : createFakeApplicationInfo(version, MC_PACKAGE_NAME);
                sourceIntent.putExtra("MC_SRC", mcInfo.sourceDir);
                if (mcInfo.splitSourceDirs != null) {
                    sourceIntent.putExtra("MC_SPLIT_SRC",
                            new ArrayList<>(Arrays.asList(mcInfo.splitSourceDirs)));
                }
                sourceIntent.putExtra("MODS_ENABLED", modsEnabled);
                sourceIntent.putExtra("MINECRAFT_VERSION", version.versionCode);
                sourceIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName);

                boolean loadMaesdk     = shouldLoadMaesdk(version);
                boolean loadHttpClient = shouldLoadHttpClient(version);
                boolean loadPlayFab    = shouldLoadPlayFab(version);
                boolean loadNewLibs    = shouldLoadNewVersionLibs(version);

                Log.i(TAG, "Version " + version.versionCode
                        + " | maesdk=" + loadMaesdk
                        + " | httpClient=" + loadHttpClient
                        + " | playFab=" + loadPlayFab
                        + " | newLibs=" + loadNewLibs);

                // ─── Correct loading order ────────────────────────────────────
                // CRITICAL: libmtbinloader2.so (shader hooks) MUST be loaded
                // before libminecraftpe.so. GamePackageManager.loadAllLibraries()
                // guarantees this order internally.
                // ─────────────────────────────────────────────────────────────

                if (loadMaesdk) {
                    // Full modern path — loadAllLibraries handles the correct order:
                    //   pre-game libs → system hook libs (incl. mtbinloader2) → minecraftpe
                    Set<String> excludeLibs = new HashSet<>();
                    if (loadHttpClient) {
                        // HttpClient & c++_shared will be loaded first explicitly below
                        excludeLibs.add("c++_shared");
                        excludeLibs.add("HttpClient.Android");
                    }
                    if (!loadPlayFab) {
                        excludeLibs.add("PlayFabMultiplayer");
                    }

                    // Pre-load HttpClient before everything else if needed
                    if (loadHttpClient) {
                        gameManager.loadLibrary("c++_shared");
                        if (gameManager.loadLibrary("HttpClient.Android")) {
                            Log.d(TAG, "Loaded libHttpClient.Android.so");
                        } else {
                            Log.w(TAG, "HttpClient.Android not found — continuing");
                        }
                    }

                    // Load remaining libs in safe order (mtbinloader2 before minecraftpe)
                    gameManager.loadAllLibraries(excludeLibs);

                } else {
                    // Legacy path (< 1.21.80): manual load — mtbinloader2 BEFORE minecraftpe
                    gameManager.loadLibrary("c++_shared");
                    gameManager.loadLibrary("fmod");
                    gameManager.loadLibrary("MediaDecoders_Android");
                    // Shader hooks first!
                    gameManager.loadLibrary("pairipcore");
                    gameManager.loadLibrary("mtbinloader2");
                    // Game lib last
                    gameManager.loadLibrary("minecraftpe");
                }

                activity.runOnUiThread(() -> {
                    dismissLoading();
                    activity.startActivity(sourceIntent);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch Minecraft activity: " + e.getMessage(), e);
                activity.runOnUiThread(() -> {
                    dismissLoading();
                    Toast.makeText(context, "Failed to launch: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ─── Library need checks ──────────────────────────────────────────────────

    private boolean shouldLoadMaesdk(GameVersion version) {
        if (version == null || version.versionCode == null) return false;
        String v = version.versionCode;
        return isVersionAtLeast(v, isBeta(v) ? MAESDK_BETA : MAESDK_STABLE);
    }

    private boolean shouldLoadHttpClient(GameVersion version) {
        if (version == null || version.versionCode == null) return false;
        String v = version.versionCode;
        return isVersionAtLeast(v, isBeta(v) ? HTTPCLIENT_BETA : HTTPCLIENT_STABLE);
    }

    private boolean shouldLoadPlayFab(GameVersion version) {
        if (version == null || version.versionCode == null) return false;
        String v = version.versionCode;
        return isVersionAtLeast(v, isBeta(v) ? HTTPCLIENT_BETA : HTTPCLIENT_STABLE);
    }

    private boolean shouldLoadNewVersionLibs(GameVersion version) {
        if (version == null || version.versionCode == null) return false;
        String v = version.versionCode;
        return isVersionAtLeast(v, isBeta(v) ? NEW_LIBS_BETA : NEW_LIBS_STABLE);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** True if this is a beta build (4 numeric parts, e.g. "1.26.23.1"). */
    private boolean isBeta(String versionCode) {
        if (versionCode == null) return false;
        return versionCode.replaceAll("[^0-9.]", "").split("\\.").length >= 4;
    }

    /** Numeric version comparison — works for any depth. */
    private boolean isVersionAtLeast(String current, String target) {
        try {
            String[] cur = current.replaceAll("[^0-9.]", "").split("\\.");
            String[] tgt = target.split("\\.");
            int max = Math.max(cur.length, tgt.length);
            for (int i = 0; i < max; i++) {
                int c = i < cur.length ? Integer.parseInt(cur[i]) : 0;
                int t = i < tgt.length ? Integer.parseInt(tgt[i]) : 0;
                if (c > t) return true;
                if (c < t) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            Log.w(TAG, "Version parse error: " + current + " vs " + target);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void dismissLoading() {
        try {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        } catch (Exception ignored) {
        } finally {
            loadingDialog = null;
        }
    }

    private void showLaunchErrorOnUi(String message) {
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> Toast.makeText(
                activity, "Failed to launch Minecraft: " + message,
                Toast.LENGTH_LONG).show()
        );
    }
}

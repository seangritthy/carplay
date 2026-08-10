package com.carplay.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.StrictMode;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

public class AppUpdater {

    private static final String GITHUB_RELEASES_URL = "https://api.github.com/repos/seangritthy/carplay/releases";
    private static File pendingApkFile = null;

    public static void checkForUpdates(final Activity activity, final boolean showNoUpdateToast) {
        new CheckUpdateTask(activity, showNoUpdateToast).execute();
    }

    public static void checkResumeInstall(Activity activity) {
        if (pendingApkFile != null && pendingApkFile.exists()) {
            if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) {
                File apkToInstall = pendingApkFile;
                pendingApkFile = null;
                installApk(activity, apkToInstall);
            }
        }
    }

    private static class CheckUpdateTask extends AsyncTask<Void, Void, JSONObject> {
        private final Activity activity;
        private final boolean showNoUpdateToast;

        CheckUpdateTask(Activity activity, boolean showNoUpdateToast) {
            this.activity = activity;
            this.showNoUpdateToast = showNoUpdateToast;
        }

        @Override
        protected JSONObject doInBackground(Void... params) {
            try {
                URL url = new URL(GITHUB_RELEASES_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "CarPlayRadioApp");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONArray releases = new JSONArray(sb.toString());
                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject rel = releases.getJSONObject(i);
                        String tag = rel.optString("tag_name", "").toLowerCase();
                        if (tag.startsWith("v") || tag.startsWith("carplay")) {
                            return rel;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(JSONObject result) {
            if (activity == null || activity.isFinishing()) return;

            if (result == null) {
                if (showNoUpdateToast) {
                    Toast.makeText(activity, "Failed to check for updates.", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            try {
                String latestTag = result.optString("tag_name", "").replace("v", "").replace("carplay-", "").trim();
                String releaseNotes = result.optString("body", "Bug fixes, CarPlay performance and driving map updates.");
                String downloadUrl = null;

                JSONArray assets = result.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "").toLowerCase();
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                String currentVersion = getCurrentVersionName(activity);

                if (downloadUrl == null) {
                    downloadUrl = "https://github.com/seangritthy/carplay/releases/latest/download/android-carplay-app.apk";
                }

                if (isNewerVersion(currentVersion, latestTag) || showNoUpdateToast) {
                    showUpdateDialog(activity, latestTag.isEmpty() ? "1.5.6" : latestTag, releaseNotes, downloadUrl);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName.replace("v", "").trim();
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }

    private static boolean isNewerVersion(String current, String latest) {
        if (current == null || latest == null) return false;
        String[] currParts = current.split("\\.");
        String[] lateParts = latest.split("\\.");

        int length = Math.max(currParts.length, lateParts.length);
        for (int i = 0; i < length; i++) {
            int c = i < currParts.length ? parseSafeInt(currParts[i]) : 0;
            int l = i < lateParts.length ? parseSafeInt(lateParts[i]) : 0;
            if (l > c) return true;
            if (c > l) return false;
        }
        return false;
    }

    private static int parseSafeInt(String val) {
        try {
            return Integer.parseInt(val.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void showUpdateDialog(final Activity activity, final String newVersion, String releaseNotes, final String downloadUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("CarPlay Update Available (v" + newVersion + ")")
                .setMessage("A new version of Android CarPlay Radio is ready!\n\nWhat's New:\n" + releaseNotes + "\n\nClick Install to launch Package Installer now.")
                .setPositiveButton("Install Update", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        downloadAndInstallApk(activity, downloadUrl);
                    }
                })
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private static void downloadAndInstallApk(final Activity activity, final String downloadUrl) {
        final ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Downloading Update");
        progressDialog.setMessage("Downloading the latest CarPlay update APK...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.setMax(100);
        progressDialog.show();

        new AsyncTask<Void, Integer, File>() {
            private HttpURLConnection openConnectionWithRedirects(String urlStr) throws Exception {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "VDomovCarPlayApp");
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    return openConnectionWithRedirects(newUrl);
                }
                return conn;
            }

            @Override
            protected File doInBackground(Void... params) {
                try {
                    HttpURLConnection conn = openConnectionWithRedirects(downloadUrl);
                    int fileLength = conn.getContentLength();
                    File apkFile = new File(activity.getExternalFilesDir(null), "vdomov-carplay-update.apk");
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    InputStream input = conn.getInputStream();
                    FileOutputStream output = new FileOutputStream(apkFile);

                    byte[] data = new byte[4096];
                    long total = 0;
                    int count;
                    while ((count = input.read(data)) != -1) {
                        total += count;
                        if (fileLength > 0) {
                            publishProgress((int) (total * 100 / fileLength));
                        }
                        output.write(data, 0, count);
                    }

                    output.flush();
                    output.close();
                    input.close();

                    apkFile.setReadable(true, false);
                    return apkFile;
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                progressDialog.setProgress(values[0]);
            }

            @Override
            protected void onPostExecute(File apkFile) {
                progressDialog.dismiss();
                if (apkFile != null && apkFile.exists()) {
                    installApk(activity, apkFile);
                } else {
                    Toast.makeText(activity, "Failed to download update APK.", Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) return;

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    pendingApkFile = apkFile;
                    Toast.makeText(activity, "Please enable 'Allow from this source' to install the CarPlay update.", Toast.LENGTH_LONG).show();
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    settingsIntent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(settingsIntent);
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 24) {
                Method m = StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                m.invoke(null);
            }
        } catch (Exception ignored) {}

        try {
            apkFile.setReadable(true, false);
            Uri apkUri = Uri.fromFile(apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Directly target system Package Installer to bypass app chooser dialog
            String[] installerPackages = new String[] {
                "com.android.packageinstaller",
                "com.google.android.packageinstaller"
            };

            boolean launched = false;
            for (String pkg : installerPackages) {
                try {
                    Intent pkgIntent = new Intent(intent);
                    pkgIntent.setPackage(pkg);
                    if (pkgIntent.resolveActivity(activity.getPackageManager()) != null) {
                        activity.startActivity(pkgIntent);
                        launched = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!launched) {
                Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                if (installIntent.resolveActivity(activity.getPackageManager()) != null) {
                    activity.startActivity(installIntent);
                } else {
                    activity.startActivity(intent);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(activity, "Error launching Package Installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

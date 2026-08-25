package app.multiai.mobile.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this, getString(R.string.scr_about_title), false);
        LinearLayout root = sc.content;

        LinearLayout c = SettingsUI.card(this);
        c.addView(SettingsUI.row(this, 0, getString(R.string.scr_about_help_center), null,
                () -> openUrl("https://multiai.store/dashboard#/docs")));
        c.addView(SettingsUI.divider(this));
        c.addView(SettingsUI.row(this, 0, getString(R.string.scr_about_terms), null,
                () -> openUrl("https://multiai.store/terms.html")));
        c.addView(SettingsUI.divider(this));
        c.addView(SettingsUI.row(this, 0, getString(R.string.scr_about_privacy), null,
                () -> openUrl("https://multiai.store/privacy.html")));
        c.addView(SettingsUI.divider(this));

        String ver = "1.0";
        int code = 1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = pi.versionCode;
        } catch (Exception ignored) {}
        // App icon + name + version. Use the full launcher mipmap (ic_launcher) —
        // NOT ic_launcher_foreground, which is only the transparent adaptive layer
        // and renders as an empty/tinted blob at this size.
        View appRow = SettingsUI.rowNoChevron(this, R.mipmap.ic_launcher,
                getString(R.string.scr_about_app_name), ver + " (" + code + ")", null);
        // SettingsUI tints every row icon grey; the launcher icon must keep its own
        // colors, so clear the filter and round the square PNG into an app-icon tile.
        LinearLayout appRowLl = (LinearLayout) appRow;
        if (appRowLl.getChildCount() > 0 && appRowLl.getChildAt(0) instanceof ImageView) {
            ImageView icon = (ImageView) appRowLl.getChildAt(0);
            icon.setColorFilter(null);
            int tile = SettingsUI.dp(this, 28);
            icon.setLayoutParams(new LinearLayout.LayoutParams(tile, tile));
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            SettingsUI.dp(AboutActivity.this, 7));
                }
            });
        }
        c.addView(appRow);
        root.addView(c);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_about_open_link_error));
        }
    }
}

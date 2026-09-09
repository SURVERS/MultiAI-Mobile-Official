package app.multiai.mobile.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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
        View appRow = SettingsUI.rowNoChevron(this, R.mipmap.ic_launcher,
                getString(R.string.scr_about_app_name), ver + " (" + code + ")", null);
        applyAppIcon(appRow);
        c.addView(appRow);
        root.addView(c);
    }

    /**
     * buildRow оборачивает иконку строки в круглый tonal-FrameLayout и тинтует её
     * серым — для лаунчер-иконки это давало пустой блоб (первый ребёнок строки —
     * обёртка, а не ImageView, поэтому прежняя проверка instanceof не срабатывала).
     * Достаём реальную ImageView из обёртки, убираем серый круг и тинт, и показываем
     * настоящую иконку приложения (adaptive → полностью скомпонованная) плиткой.
     */
    private void applyAppIcon(View appRow) {
        if (!(appRow instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) appRow;
        if (row.getChildCount() == 0 || !(row.getChildAt(0) instanceof FrameLayout)) return;
        FrameLayout iconWrap = (FrameLayout) row.getChildAt(0);
        iconWrap.setBackground(null);
        if (iconWrap.getChildCount() == 0 || !(iconWrap.getChildAt(0) instanceof ImageView)) return;
        ImageView icon = (ImageView) iconWrap.getChildAt(0);
        icon.setColorFilter(null);

        Drawable appDrawable;
        try {
            appDrawable = getPackageManager().getApplicationIcon(getPackageName());
        } catch (Exception e) {
            appDrawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher);
        }
        icon.setImageDrawable(appDrawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int tile = SettingsUI.dp(this, 36);
        icon.setLayoutParams(new FrameLayout.LayoutParams(tile, tile));
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        SettingsUI.dp(AboutActivity.this, 9));
            }
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_about_open_link_error));
        }
    }
}

package app.multiai.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.ChatManager;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;

public class DataControlsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this, getString(R.string.data_controls), false);
        LinearLayout root = sc.content;

        // ── Export ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout c2 = SettingsUI.card(this);
        c2.addView(SettingsUI.row(this, 0, getString(R.string.scr_data_export), null, () ->
                ModernToast.show(this, getString(R.string.scr_data_export_msg))));
        root.addView(c2);

        // ── История чата ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout c4 = SettingsUI.card(this);
        c4.addView(SettingsUI.row(this, 0, getString(R.string.archive_view), null,
                () -> startActivity(new Intent(this, ArchivedChatsActivity.class))));
        c4.addView(SettingsUI.divider(this));
        c4.addView(SettingsUI.row(this, 0, getString(R.string.archive_all_history), null,
                () -> ModernDialog.show(this,
                        getString(R.string.scr_data_archive_confirm_title),
                        getString(R.string.scr_data_archive_confirm_msg),
                        getString(R.string.chat_cancel), getString(R.string.scr_data_archive_button), false,
                        () -> {
                            int workCount = new ChatManager(this).archiveAllActive();
                            int chatCount = new ChatManager(this, "multiai_chatmode_chats")
                                    .archiveAllActive();
                            int total = workCount + chatCount;
                            ModernToast.show(this, total == 0
                                    ? getString(R.string.scr_data_archive_none)
                                    : getString(R.string.scr_data_archive_count, total));
                        })));
        c4.addView(SettingsUI.divider(this));
        c4.addView(SettingsUI.rowDanger(this, getString(R.string.clear_chat_history), () ->
                ModernDialog.show(this,
                        getString(R.string.scr_data_clear_confirm_title),
                        getString(R.string.scr_data_clear_confirm_msg),
                        getString(R.string.chat_cancel), getString(R.string.scr_data_clear_button), true,
                        () -> {
                            new ChatManager(this).clearAllChats();
                            new ChatManager(this, "multiai_chatmode_chats").clearAllChats();
                            ModernToast.show(this, getString(R.string.scr_data_history_cleared));
                        })));
        root.addView(c4);
    }
}

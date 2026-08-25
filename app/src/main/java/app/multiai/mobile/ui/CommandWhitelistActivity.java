package app.multiai.mobile.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.WorkModeAccess;

/**
 * Full screen for command rules in Work Mode.
 *
 * <p>Global block rules always win over any allow rule. Session rules are held
 * in memory per Project-chat session and disappear after an app restart.</p>
 */
public class CommandWhitelistActivity extends AppCompatActivity {

    private enum RuleScope {
        GLOBAL_ALLOW,
        GLOBAL_BLOCK,
        SESSION_ALLOW
    }

    private LinearLayout content;
    private String selectedSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        if (savedInstanceState != null) {
            selectedSessionId = savedInstanceState.getString("selected_session_id");
        }

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(
                this, getString(R.string.whitelist_screen_title), false);
        content = scaffold.content;
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("selected_session_id", selectedSessionId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rules can be granted from an approval sheet while this screen is paused.
        if (content != null) render();
    }

    private void render() {
        content.removeAllViews();

        TextView description = new TextView(this);
        description.setText(R.string.whitelist_screen_description);
        description.setTextColor(SettingsUI.colorSubtext(this));
        description.setTextSize(13);
        description.setLineSpacing(SettingsUI.dp(this, 2), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.leftMargin = SettingsUI.dp(this, 20);
        descLp.rightMargin = SettingsUI.dp(this, 20);
        descLp.topMargin = SettingsUI.dp(this, 8);
        description.setLayoutParams(descLp);
        content.addView(description);

        addSection(RuleScope.GLOBAL_ALLOW, WorkModeAccess.persistentCommands(this));
        addSection(RuleScope.GLOBAL_BLOCK, WorkModeAccess.blockedCommands(this));

        WorkModeAccess.SessionScope session = selectedSession();
        addSessionPicker(session);
        if (session != null) {
            addSection(RuleScope.SESSION_ALLOW,
                    WorkModeAccess.sessionCommands(session.getId()));
        }
        content.addView(SettingsUI.spacer(this, 24));
    }

    private WorkModeAccess.SessionScope selectedSession() {
        List<WorkModeAccess.SessionScope> scopes = WorkModeAccess.sessionScopes();
        if (scopes.isEmpty()) return null;
        for (WorkModeAccess.SessionScope scope : scopes) {
            if (scope.getId().equals(selectedSessionId)) return scope;
        }
        selectedSessionId = scopes.get(0).getId();
        return scopes.get(0);
    }

    private void addSessionPicker(WorkModeAccess.SessionScope session) {
        content.addView(SettingsUI.sectionLabel(this,
                getString(R.string.whitelist_session_picker)));
        LinearLayout card = SettingsUI.card(this);
        String subtitle = session == null ? getString(R.string.whitelist_session_unnamed)
                : sessionLabel(session);
        card.addView(SettingsUI.row(this, R.drawable.ic_terminal_tools,
                getString(R.string.whitelist_session_picker), subtitle,
                () -> showSessionPicker(card)));
        content.addView(card);
        content.addView(SettingsUI.spacer(this, 10));
    }

    private String sessionLabel(WorkModeAccess.SessionScope session) {
        String label = session.getLabel() == null ? "" : session.getLabel().trim();
        if (label.isEmpty()) label = getString(R.string.whitelist_session_unnamed);
        return session.isActive()
                ? getString(R.string.whitelist_session_current, label) : label;
    }

    private void showSessionPicker(View anchor) {
        List<WorkModeAccess.SessionScope> scopes = WorkModeAccess.sessionScopes();
        List<ModernSheet.MenuItem> entries = new ArrayList<>();
        for (WorkModeAccess.SessionScope scope : scopes) {
            entries.add(new ModernSheet.MenuItem(sessionLabel(scope)));
        }
        ModernMenu.show(anchor, getString(R.string.whitelist_session_picker), entries,
                (index, item) -> {
                    if (index < 0 || index >= scopes.size()) return;
                    selectedSessionId = scopes.get(index).getId();
                    render();
                });
    }

    private void addSection(RuleScope scope, List<String> commands) {
        content.addView(SettingsUI.sectionLabel(this, sectionTitle(scope)));

        TextView note = new TextView(this);
        note.setText(sectionNote(scope));
        note.setTextColor(SettingsUI.colorSubtext(this));
        note.setTextSize(12);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.leftMargin = SettingsUI.dp(this, 20);
        noteLp.rightMargin = SettingsUI.dp(this, 20);
        noteLp.bottomMargin = SettingsUI.dp(this, 10);
        note.setLayoutParams(noteLp);
        content.addView(note);

        LinearLayout card = SettingsUI.card(this);
        if (commands.isEmpty()) {
            card.addView(emptyRow());
        } else {
            for (int i = 0; i < commands.size(); i++) {
                if (i > 0) card.addView(SettingsUI.divider(this));
                card.addView(commandRow(commands.get(i), scope));
            }
        }
        content.addView(card);

        content.addView(SettingsUI.spacer(this, 10));
        LinearLayout actions = SettingsUI.card(this);
        actions.addView(SettingsUI.rowNoChevron(this, R.drawable.ic_plus,
                addLabel(scope), null, () -> showEditor(scope, null)));
        if (!commands.isEmpty()) {
            actions.addView(SettingsUI.divider(this));
            actions.addView(SettingsUI.rowNoChevron(this, R.drawable.ic_trash,
                    getString(R.string.whitelist_clear), null,
                    () -> confirmClear(scope, commands.size())));
        }
        content.addView(actions);
        content.addView(SettingsUI.spacer(this, 10));
    }

    private String sectionTitle(RuleScope scope) {
        if (scope == RuleScope.GLOBAL_ALLOW) return getString(R.string.whitelist_section_global);
        if (scope == RuleScope.GLOBAL_BLOCK) return getString(R.string.whitelist_section_blocked);
        return getString(R.string.whitelist_section_session);
    }

    private String sectionNote(RuleScope scope) {
        if (scope == RuleScope.GLOBAL_ALLOW) return getString(R.string.whitelist_section_global_note);
        if (scope == RuleScope.GLOBAL_BLOCK) return getString(R.string.whitelist_section_blocked_note);
        return getString(R.string.whitelist_section_session_note);
    }

    private String addLabel(RuleScope scope) {
        return scope == RuleScope.GLOBAL_BLOCK
                ? getString(R.string.whitelist_block_add) : getString(R.string.whitelist_add);
    }

    private View emptyRow() {
        TextView empty = new TextView(this);
        empty.setText(R.string.whitelist_empty);
        empty.setTextColor(SettingsUI.colorSubtext(this));
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 22),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 22));
        return empty;
    }

    /** One command: monospace prompt marker, tap to edit, trailing delete button. */
    private View commandRow(String command, RuleScope scope) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 8), SettingsUI.dp(this, 12));
        row.setClickable(true);
        row.setOnClickListener(v -> showEditor(scope, command));

        TextView prompt = new TextView(this);
        prompt.setText(">_");
        prompt.setTextColor(scope == RuleScope.GLOBAL_BLOCK
                ? SettingsUI.colorDanger(this) : SettingsUI.colorAccent(this));
        prompt.setTextSize(14);
        prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams promptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptLp.setMarginEnd(SettingsUI.dp(this, 10));
        row.addView(prompt, promptLp);

        TextView text = new TextView(this);
        text.setText(command);
        text.setTextColor(SettingsUI.colorText(this));
        text.setTextSize(13);
        text.setTypeface(Typeface.MONOSPACE);
        text.setMaxLines(3);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView delete = new ImageView(this);
        delete.setImageResource(R.drawable.ic_delete_outline);
        delete.setColorFilter(SettingsUI.colorDanger(this));
        delete.setPadding(SettingsUI.dp(this, 9), SettingsUI.dp(this, 9),
                SettingsUI.dp(this, 9), SettingsUI.dp(this, 9));
        delete.setClickable(true);
        delete.setContentDescription(getString(R.string.whitelist_delete));
        delete.setOnClickListener(v -> confirmDelete(scope, command));
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 40), SettingsUI.dp(this, 40));
        deleteLp.setMarginStart(SettingsUI.dp(this, 6));
        row.addView(delete, deleteLp);
        return row;
    }

    private void showEditor(RuleScope scope, String original) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        final EditText input = new EditText(this);
        input.setText(original == null ? "" : original);
        input.setSelectAllOnFocus(original != null);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setTextSize(14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextColor(SettingsUI.colorText(this));
        input.setHintTextColor(SettingsUI.colorHint(this));
        input.setHint(R.string.whitelist_input_example);
        input.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(SettingsUI.colorSurfaceTonal(this));
        inputBg.setCornerRadius(SettingsUI.dp(this, 12));
        inputBg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        input.setBackground(inputBg);
        AccentColor.applyCursorTint(input, SettingsUI.colorAccent(this));
        body.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ModernDialog.show(this,
                editorTitle(scope, original == null),
                editorHint(scope),
                body,
                getString(android.R.string.cancel),
                getString(R.string.whitelist_save),
                false,
                () -> {
                    String command = input.getText() == null
                            ? "" : input.getText().toString().trim();
                    if (command.isEmpty()) {
                        ModernToast.show(this, getString(R.string.whitelist_empty_error));
                        return;
                    }
                    saveRule(scope, original, command);
                    ModernToast.show(this, getString(R.string.whitelist_saved));
                    render();
                });
    }

    private String editorTitle(RuleScope scope, boolean adding) {
        if (scope == RuleScope.GLOBAL_BLOCK) {
            return getString(adding ? R.string.whitelist_block_add_title
                    : R.string.whitelist_block_edit_title);
        }
        return getString(adding ? R.string.whitelist_add_title : R.string.whitelist_edit_title);
    }

    private String editorHint(RuleScope scope) {
        return getString(scope == RuleScope.GLOBAL_BLOCK
                ? R.string.whitelist_block_input_hint : R.string.whitelist_input_hint);
    }

    private void saveRule(RuleScope scope, String oldCommand, String newCommand) {
        if (scope == RuleScope.GLOBAL_ALLOW) {
            WorkModeAccess.replacePersistentCommand(this, oldCommand, newCommand);
        } else if (scope == RuleScope.GLOBAL_BLOCK) {
            WorkModeAccess.replaceBlockedCommand(this, oldCommand, newCommand);
        } else {
            WorkModeAccess.replaceSessionCommand(selectedSessionId, oldCommand, newCommand);
        }
    }

    private void confirmDelete(RuleScope scope, String command) {
        boolean block = scope == RuleScope.GLOBAL_BLOCK;
        ModernDialog.show(this,
                getString(block ? R.string.whitelist_block_delete_title
                        : R.string.whitelist_delete_title),
                getString(block ? R.string.whitelist_block_delete_message
                        : R.string.whitelist_delete_message, command),
                getString(android.R.string.cancel),
                getString(R.string.whitelist_delete),
                true,
                () -> {
                    if (scope == RuleScope.GLOBAL_ALLOW) {
                        WorkModeAccess.removePersistentCommand(this, command);
                    } else if (scope == RuleScope.GLOBAL_BLOCK) {
                        WorkModeAccess.removeBlockedCommand(this, command);
                    } else {
                        WorkModeAccess.removeSessionCommand(selectedSessionId, command);
                    }
                    ModernToast.show(this, getString(R.string.whitelist_deleted));
                    render();
                });
    }

    private void confirmClear(RuleScope scope, int count) {
        boolean block = scope == RuleScope.GLOBAL_BLOCK;
        ModernDialog.show(this,
                getString(block ? R.string.whitelist_block_clear_title
                        : R.string.whitelist_clear_title),
                getString(block ? R.string.whitelist_block_clear_message
                        : R.string.whitelist_clear_message, count),
                getString(android.R.string.cancel),
                getString(R.string.whitelist_clear),
                true,
                () -> {
                    if (scope == RuleScope.GLOBAL_ALLOW) {
                        WorkModeAccess.clearPersistentCommands(this);
                    } else if (scope == RuleScope.GLOBAL_BLOCK) {
                        WorkModeAccess.clearBlockedCommands(this);
                    } else {
                        WorkModeAccess.clearSessionCommands(selectedSessionId);
                    }
                    ModernToast.show(this, getString(R.string.whitelist_deleted));
                    render();
                });
    }
}

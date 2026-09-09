package app.multiai.mobile.ui;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.ChatManager;
import app.multiai.mobile.utils.WorkModeAccess;

/**
 * Full screen for command rules in Work Mode.
 *
 * <p>Global block rules always win over any allow rule. Session rules are held
 * in memory per Project-chat session and disappear after an app restart.</p>
 */
public class CommandWhitelistActivity extends AppCompatActivity {

    private static final int SESSION_PAGE_SIZE = 6;

    private enum RuleScope {
        GLOBAL_ALLOW,
        GLOBAL_BLOCK,
        SESSION_ALLOW
    }

    private static final class SessionOption {
        final String id;
        final String label;
        final boolean active;
        final boolean archived;

        SessionOption(String id, String label, boolean active, boolean archived) {
            this.id = id;
            this.label = label;
            this.active = active;
            this.archived = archived;
        }
    }

    private LinearLayout content;
    private String selectedSessionId;
    /** 0 = Разрешённые, 1 = Запрещённые, 2 = Сессии (табы как в профиле). */
    private int currentTab;
    private TextView[] tabViews;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        if (savedInstanceState != null) {
            selectedSessionId = savedInstanceState.getString("selected_session_id");
            currentTab = savedInstanceState.getInt("current_tab", 0);
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
        outState.putInt("current_tab", currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rules can be granted from an approval sheet while this screen is paused.
        if (content != null) render();
    }

    private void render() {
        content.removeAllViews();

        // Три вкладки-пилюли как в профиле: Разрешённые | Запрещённые | Сессии.
        // Каждая показывает свою область правил — без раскрытия «Дополнительно».
        content.addView(buildTabs());
        content.addView(SettingsUI.spacer(this, 8));

        if (currentTab == 0) {
            addSection(RuleScope.GLOBAL_ALLOW, WorkModeAccess.persistentCommands(this));
        } else if (currentTab == 1) {
            addSection(RuleScope.GLOBAL_BLOCK, WorkModeAccess.blockedCommands(this));
        } else {
            SessionOption session = selectedSession();
            addSessionPicker(session);
            if (session != null) {
                addSection(RuleScope.SESSION_ALLOW, WorkModeAccess.sessionCommands(session.id));
            }
        }
        content.addView(SettingsUI.spacer(this, 24));
    }

    /** Горизонтальная лента вкладок-пилюль с иконками (паттерн ProfileActivity). */
    private View buildTabs() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = SettingsUI.dp(this, 16);
        row.setPadding(pad, SettingsUI.dp(this, 4), pad, SettingsUI.dp(this, 4));
        scroller.addView(row);

        String[] labels = {
                getString(R.string.whitelist_section_global),
                getString(R.string.whitelist_section_blocked),
                getString(R.string.whitelist_tab_sessions)
        };
        int[] icons = {R.drawable.ic_rule_allowed, R.drawable.ic_rule_blocked,
                R.drawable.ic_rule_sessions};
        tabViews = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(labels[i]);
            tab.setTextSize(14);
            tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tab.setGravity(Gravity.CENTER_VERTICAL);
            int tp = SettingsUI.dp(this, 15);
            int tpv = SettingsUI.dp(this, 9);
            tab.setPadding(tp, tpv, tp, tpv);
            Drawable icon = ContextCompat.getDrawable(this, icons[i]);
            if (icon != null) {
                icon = icon.mutate();
                int sz = SettingsUI.dp(this, 16);
                icon.setBounds(0, 0, sz, sz);
                tab.setCompoundDrawablePadding(SettingsUI.dp(this, 7));
                tab.setCompoundDrawables(icon, null, null, null);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = SettingsUI.dp(this, 8);
            tab.setLayoutParams(lp);
            SettingsUI.attachPressAnimation(tab, 0.96f);
            tab.setOnClickListener(v -> {
                if (currentTab != idx) {
                    currentTab = idx;
                    render();
                }
            });
            tabViews[i] = tab;
            row.addView(tab);
        }
        restyleTabs();
        return scroller;
    }

    /** Красит выбранную вкладку акцентом, остальные — tonal (как в профиле). */
    private void restyleTabs() {
        if (tabViews == null) return;
        int accent = SettingsUI.colorAccent(this);
        int onAccent = ContextCompat.getColor(this, R.color.icon_on_accent);
        int secondary = SettingsUI.colorSubtext(this);
        for (int i = 0; i < tabViews.length; i++) {
            boolean selected = i == currentTab;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(SettingsUI.dp(this, 20));
            bg.setColor(selected ? accent : SettingsUI.colorSurfaceTonal(this));
            tabViews[i].setBackground(bg);
            tabViews[i].setTextColor(selected ? onAccent : secondary);
            Drawable icon = tabViews[i].getCompoundDrawables()[0];
            if (icon != null) icon.setTint(selected ? onAccent : secondary);
        }
    }

    /** Пояснительный абзац в отступах экрана настроек. */
    private void addLead(String text) {
        TextView lead = new TextView(this);
        lead.setText(text);
        lead.setTextColor(SettingsUI.colorSubtext(this));
        lead.setTextSize(13);
        lead.setLineSpacing(SettingsUI.dp(this, 2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = SettingsUI.dp(this, 20);
        lp.rightMargin = SettingsUI.dp(this, 20);
        lp.topMargin = SettingsUI.dp(this, 8);
        lead.setLayoutParams(lp);
        content.addView(lead);
    }

    /** Reads every stored Project (Work Mode) chat, then keeps an unsaved current draft visible. */
    private List<SessionOption> workModeSessions() {
        List<SessionOption> result = new ArrayList<>();
        Set<String> knownIds = new HashSet<>();
        String activeId = WorkModeAccess.activeSessionId();
        SessionOption activeOption = null;

        for (Chat chat : new ChatManager(this).loadChats()) {
            if (chat == null || chat.getId() == null || chat.getId().trim().isEmpty()) continue;
            String id = chat.getId().trim();
            if (!knownIds.add(id)) continue;
            String label = chat.getTitle() == null ? "" : chat.getTitle().trim();
            WorkModeAccess.ensureSession(id, label);
            SessionOption option = new SessionOption(id, label, id.equals(activeId),
                    chat.isArchived());
            if (option.active) activeOption = option;
            else result.add(option);
        }

        if (activeOption != null) {
            result.add(0, activeOption);
        } else if (!activeId.isEmpty()) {
            String activeLabel = "";
            for (WorkModeAccess.SessionScope scope : WorkModeAccess.sessionScopes()) {
                if (activeId.equals(scope.getId())) {
                    activeLabel = scope.getLabel();
                    break;
                }
            }
            result.add(0, new SessionOption(activeId, activeLabel, true, false));
        }
        return result;
    }

    private SessionOption selectedSession() {
        List<SessionOption> sessions = workModeSessions();
        if (sessions.isEmpty()) return null;
        for (SessionOption session : sessions) {
            if (session.id.equals(selectedSessionId)) {
                WorkModeAccess.ensureSession(session.id, session.label);
                return session;
            }
        }
        selectedSessionId = sessions.get(0).id;
        WorkModeAccess.ensureSession(sessions.get(0).id, sessions.get(0).label);
        return sessions.get(0);
    }

    private void addSessionPicker(SessionOption session) {
        content.addView(SettingsUI.sectionLabel(this,
                getString(R.string.whitelist_session_picker)));
        LinearLayout card = SettingsUI.card(this);
        String subtitle = session == null ? getString(R.string.whitelist_session_none)
                : sessionLabel(session);
        card.addView(SettingsUI.row(this, R.drawable.ic_chat,
                getString(R.string.whitelist_session_picker), subtitle,
                () -> showSessionPicker(card)));
        content.addView(card);
        content.addView(SettingsUI.spacer(this, 10));
    }

    private String sessionLabel(SessionOption session) {
        String label = session.label == null ? "" : session.label.trim();
        if (label.isEmpty()) label = getString(R.string.whitelist_session_unnamed);
        if (session.archived) label = getString(R.string.whitelist_session_archived, label);
        return session.active ? getString(R.string.whitelist_session_current, label) : label;
    }

    private void showSessionPicker(View anchor) {
        List<SessionOption> sessions = workModeSessions();
        if (sessions.isEmpty()) {
            ModernToast.show(this, getString(R.string.whitelist_session_none));
            return;
        }
        int selectedIndex = 0;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id.equals(selectedSessionId)) {
                selectedIndex = i;
                break;
            }
        }
        int[] page = { selectedIndex / SESSION_PAGE_SIZE };
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        renderSessionPickerPage(body, sessions, page, sheet);
        sheet[0] = ModernSheet.custom(this, getString(R.string.whitelist_session_picker), body);
    }

    private void renderSessionPickerPage(LinearLayout body, List<SessionOption> sessions,
                                         int[] pageRef, android.app.Dialog[] sheetRef) {
        body.removeAllViews();
        int pageCount = Math.max(1, (sessions.size() + SESSION_PAGE_SIZE - 1) / SESSION_PAGE_SIZE);
        pageRef[0] = Math.max(0, Math.min(pageRef[0], pageCount - 1));
        int start = pageRef[0] * SESSION_PAGE_SIZE;
        int end = Math.min(start + SESSION_PAGE_SIZE, sessions.size());

        TextView counter = new TextView(this);
        counter.setText(getString(R.string.whitelist_session_page, start + 1, end, sessions.size()));
        counter.setTextColor(SettingsUI.colorSubtext(this));
        counter.setTextSize(12);
        counter.setPadding(0, 0, 0, SettingsUI.dp(this, 8));
        body.addView(counter);

        LinearLayout sessionsCard = SettingsUI.card(this);
        for (int i = start; i < end; i++) {
            SessionOption option = sessions.get(i);
            sessionsCard.addView(SettingsUI.row(this, R.drawable.ic_chat, sessionLabel(option), null,
                    () -> {
                        selectedSessionId = option.id;
                        WorkModeAccess.ensureSession(option.id, option.label);
                        if (sheetRef[0] != null) sheetRef[0].dismiss();
                        render();
                    }));
            if (i < end - 1) sessionsCard.addView(SettingsUI.divider(this));
        }
        body.addView(sessionsCard);

        if (pageCount > 1) {
            LinearLayout pager = new LinearLayout(this);
            pager.setGravity(Gravity.CENTER_VERTICAL);
            pager.setPadding(0, SettingsUI.dp(this, 12), 0, 0);
            TextView previous = paginationButton(getString(R.string.whitelist_session_previous),
                    pageRef[0] > 0, v -> {
                        pageRef[0]--;
                        renderSessionPickerPage(body, sessions, pageRef, sheetRef);
                    });
            TextView next = paginationButton(getString(R.string.whitelist_session_next),
                    pageRef[0] < pageCount - 1, v -> {
                        pageRef[0]++;
                        renderSessionPickerPage(body, sessions, pageRef, sheetRef);
                    });
            pager.addView(previous, new LinearLayout.LayoutParams(
                    0, SettingsUI.dp(this, 44), 1f));
            View spacer = new View(this);
            pager.addView(spacer, new LinearLayout.LayoutParams(SettingsUI.dp(this, 10), 1));
            pager.addView(next, new LinearLayout.LayoutParams(
                    0, SettingsUI.dp(this, 44), 1f));
            body.addView(pager);
        }
    }

    private TextView paginationButton(String label, boolean enabled, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(enabled ? SettingsUI.colorAccent(this) : SettingsUI.colorSubtext(this));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SettingsUI.colorSurfaceTonal(this));
        background.setCornerRadius(SettingsUI.dp(this, 12));
        background.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        button.setBackground(background);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
        if (enabled) button.setOnClickListener(listener);
        return button;
    }

    private void addSection(RuleScope scope, List<String> commands) {
        // Заголовок секции не показываем — его роль выполняет активная вкладка.
        TextView note = new TextView(this);
        note.setText(sectionNote(scope));
        note.setTextColor(SettingsUI.colorSubtext(this));
        note.setTextSize(12);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.leftMargin = SettingsUI.dp(this, 20);
        noteLp.rightMargin = SettingsUI.dp(this, 20);
        noteLp.topMargin = SettingsUI.dp(this, 4);
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

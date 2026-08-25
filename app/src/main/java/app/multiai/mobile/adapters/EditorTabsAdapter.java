package app.multiai.mobile.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.models.EditorTab;

public class EditorTabsAdapter extends RecyclerView.Adapter<EditorTabsAdapter.TabViewHolder> {

    public interface OnTabActionListener {
        void onTabSelected(int position);
        void onTabClosed(int position);
    }

    private final List<EditorTab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private OnTabActionListener listener;

    public void setOnTabActionListener(OnTabActionListener listener) {
        this.listener = listener;
    }

    public void setTabs(List<EditorTab> newTabs, int activeIndex) {
        tabs.clear();
        if (newTabs != null) {
            tabs.addAll(newTabs);
        }
        this.activeIndex = activeIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_editor_tab, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        holder.bind(tabs.get(position), position == activeIndex, position);
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    class TabViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout root;
        private final TextView textTabName;
        private final TextView textDirty;
        private final ImageView buttonClose;

        TabViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.tabRoot);
            textTabName = itemView.findViewById(R.id.textTabName);
            textDirty = itemView.findViewById(R.id.textTabDirty);
            buttonClose = itemView.findViewById(R.id.buttonCloseTab);
        }

        void bind(EditorTab tab, boolean active, int position) {
            textTabName.setText(tab.getName());
            textDirty.setVisibility(tab.isDirty() ? View.VISIBLE : View.GONE);

            // Resolve readable tab colors from semantic tokens so they adapt to
            // light/dark. Active tab = primary text; inactive = secondary, with
            // a muted close icon. Falls back to sensible constants if a token
            // is missing.
            android.content.Context ctx = itemView.getContext();
            int activeText = resolveColor(ctx, R.color.text_primary, 0xFFFFFFFF);
            int inactiveText = resolveColor(ctx, R.color.text_secondary, 0xFFCCCCCC);
            int mutedIcon = resolveColor(ctx, R.color.icon_muted, 0xFFB9C7DE);
            int dirtyDotActive = resolveColor(ctx, R.color.text_hint, 0xFFCCCCCC);

            if (active) {
                root.setBackgroundResource(R.drawable.editor_tab_active);
                textTabName.setTextColor(activeText);
                textDirty.setTextColor(dirtyDotActive);
                buttonClose.setColorFilter(mutedIcon);
            } else {
                root.setBackgroundResource(R.drawable.editor_tab_inactive);
                textTabName.setTextColor(inactiveText);
                textDirty.setTextColor(dirtyDotActive);
                buttonClose.setColorFilter(inactiveText);
            }

            root.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTabSelected(position);
                }
            });

            buttonClose.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTabClosed(position);
                }
            });
        }
    }

    /** Resolve a semantic color token, falling back to a hard-coded constant if missing. */
    private static int resolveColor(android.content.Context ctx, int resId, int fallback) {
        try {
            return androidx.core.content.ContextCompat.getColor(ctx, resId);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

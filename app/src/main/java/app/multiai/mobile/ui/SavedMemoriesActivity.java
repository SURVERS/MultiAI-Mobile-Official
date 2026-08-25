package app.multiai.mobile.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.R;

public class SavedMemoriesActivity extends AppCompatActivity {

    private RecyclerView recyclerMemories;
    private LinearLayout emptyState;
    private MemoriesAdapter adapter;
    private List<MemoryItem> memories = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_saved_memories);
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        // Apply system window insets so toolbar avoids the status bar
        View root = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,
                (view, insets) -> {
                    int st = insets.getSystemWindowInsetTop();
                    int sb = insets.getSystemWindowInsetBottom();
                    int sl = insets.getSystemWindowInsetLeft();
                    int sr = insets.getSystemWindowInsetRight();
                    view.setPadding(sl, st, sr, sb);
                    return androidx.core.view.WindowInsetsCompat.CONSUMED;
                });
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.requestApplyInsets(root);

        recyclerMemories = findViewById(R.id.recyclerMemories);
        emptyState = findViewById(R.id.emptyState);
        ImageButton buttonBack = findViewById(R.id.buttonBack);
        buttonBack.setOnClickListener(v -> finish());

        adapter = new MemoriesAdapter();
        recyclerMemories.setLayoutManager(new LinearLayoutManager(this));
        recyclerMemories.setAdapter(adapter);

        loadMemories();
    }

    private void loadMemories() {
        memories.clear();
        try {
            SharedPreferences prefs = getSharedPreferences("multiai_memories", MODE_PRIVATE);
            String json = prefs.getString("memories_json", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject obj = arr.getJSONObject(i);
                memories.add(new MemoryItem(
                    obj.optString("name", ""),
                    obj.optString("content", ""),
                    obj.optLong("timestamp", 0)
                ));
            }
        } catch (Exception e) {
        }

        adapter.notifyDataSetChanged();
        emptyState.setVisibility(memories.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerMemories.setVisibility(memories.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void deleteMemory(int adapterPosition) {
        try {
            SharedPreferences prefs = getSharedPreferences("multiai_memories", MODE_PRIVATE);
            String json = prefs.getString("memories_json", "[]");
            JSONArray arr = new JSONArray(json);
            // adapterPosition is reversed (newest first), so real index = arr.length() - 1 - pos
            int realIndex = arr.length() - 1 - adapterPosition;
            if (realIndex >= 0 && realIndex < arr.length()) {
                JSONArray newArr = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != realIndex) newArr.put(arr.get(i));
                }
                prefs.edit().putString("memories_json", newArr.toString()).apply();
                // Sync to cloud
                new Thread(() -> {
                    try { new app.multiai.mobile.utils.CloudSyncManager(SavedMemoriesActivity.this).pushMemories(); }
                    catch (Exception ignored) {}
                }).start();
            }
        } catch (Exception e) {
        }
        loadMemories();
    }

    static class MemoryItem {
        String name, content;
        long timestamp;
        MemoryItem(String name, String content, long timestamp) {
            this.name = name;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    class MemoriesAdapter extends RecyclerView.Adapter<MemoriesAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_memory, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            MemoryItem item = memories.get(position);
            holder.textName.setText(item.name);
            holder.textContent.setText(item.content);
            if (item.timestamp > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
                holder.textTime.setText(sdf.format(new Date(item.timestamp)));
                holder.textTime.setVisibility(View.VISIBLE);
            } else {
                holder.textTime.setVisibility(View.GONE);
            }
            holder.btnDelete.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    deleteMemory(pos);
                }
            });
        }

        @Override
        public int getItemCount() { return memories.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView textName, textContent, textTime;
            ImageButton btnDelete;
            VH(View v) {
                super(v);
                textName = v.findViewById(R.id.textMemoryName);
                textContent = v.findViewById(R.id.textMemoryContent);
                textTime = v.findViewById(R.id.textMemoryTime);
                btnDelete = v.findViewById(R.id.buttonDelete);
            }
        }
    }
}

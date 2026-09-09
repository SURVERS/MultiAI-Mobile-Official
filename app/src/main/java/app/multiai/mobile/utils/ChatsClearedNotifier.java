package app.multiai.mobile.utils;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Публюкация «все чаты очищены» из Data Controls в открытые чат-экраны.
 *
 * <p>ChatActivity/ChatModeActivity регистрируются на onCreate и на сигнал
 * сбрасываются в новый чат: иначе в памяти оставался живой currentChat,
 * который первым же updateChat воскрешал «удалённую» историю.
 */
public final class ChatsClearedNotifier {

    public interface Listener { void onAllChatsCleared(); }

    private static final List<WeakReference<Listener>> LISTENERS = new ArrayList<>();

    private ChatsClearedNotifier() {}

    public static void register(Listener listener) {
        LISTENERS.add(new WeakReference<>(listener));
    }

    public static void notifyCleared() {
        List<Listener> live = new ArrayList<>();
        for (WeakReference<Listener> ref : LISTENERS) {
            Listener l = ref.get();
            if (l instanceof Activity && (((Activity) l).isFinishing() || ((Activity) l).isDestroyed())) continue;
            if (l != null) live.add(l);
        }
        LISTENERS.clear();
        for (Listener l : live) {
            try {
                l.onAllChatsCleared();
            } catch (Exception ignored) {
            }
        }
    }
}

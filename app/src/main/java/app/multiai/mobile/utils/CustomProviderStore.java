package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.models.CustomProvider;

/**
 * Хранилище кастомных AI-провайдеров в SharedPreferences.
 * Ключ: multiaiide_custom_providers / providers_json
 */
public final class CustomProviderStore {

    private static final String PREFS = "multiaiide_custom_providers";
    private static final String KEY_PROVIDERS = "providers_json";
    private static final String KEY_LAST_SOURCE = "last_picker_source";
    private static final int SCHEMA_VERSION = 1;

    /** Префикс синтетического model id, используемого в пикере для моделей кастомных провайдеров. */
    public static final String CUSTOM_ID_PREFIX = "custom/";

    private CustomProviderStore() {}

    /** Запоминает выбранный источник в пикере моделей (null = MultiAI). */
    public static void saveLastPickerSource(android.content.Context context, String providerId) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_SOURCE, providerId == null ? "" : providerId).apply();
    }

    /** Последний выбранный источник пикера (null = MultiAI, "" = не задано). */
    public static String lastPickerSource(android.content.Context context) {
        if (context == null) return null;
        String raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LAST_SOURCE, "");
        return raw == null || raw.isEmpty() ? null : raw;
    }

    /** Строит синтетический id модели вида "custom/<providerId>/<requestName>" для пикера. */
    public static String buildModelId(String providerId, String requestName) {
        return CUSTOM_ID_PREFIX + providerId + "/" + requestName;
    }

    /** Результат разбора синтетического id: провайдер + реальное имя модели для запроса. */
    public static final class ResolvedCustomModel {
        public final CustomProvider provider;
        public final String requestName;
        /** Запись модели из хранилища (kind и метаданные), null если не найдена. */
        public final CustomProvider.ProviderModel model;

        ResolvedCustomModel(CustomProvider provider, String requestName) {
            this(provider, requestName, null);
        }

        ResolvedCustomModel(CustomProvider provider, String requestName,
                            CustomProvider.ProviderModel model) {
            this.provider = provider;
            this.requestName = requestName;
            this.model = model;
        }

        /** Эмбеддинг-модели обслуживаются только /v1/embeddings — в чат их не пускаем. */
        public boolean isEmbeddingModel() {
            return model != null && "embedding".equals(model.kind);
        }
    }

    /**
     * Разбирает {@code modelId}: если это синтетический id кастомного провайдера
     * ("custom/<providerId>/<requestName>") и провайдер существует в хранилище — возвращает
     * его вместе с реальным именем модели для запроса. Иначе — null (обычная MultiAI модель).
     */
    public static ResolvedCustomModel resolve(Context context, String modelId) {
        if (context == null || modelId == null || !modelId.startsWith(CUSTOM_ID_PREFIX)) return null;
        String rest = modelId.substring(CUSTOM_ID_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash + 1 >= rest.length()) return null;
        String providerId = rest.substring(0, slash);
        String requestName = rest.substring(slash + 1);
        CustomProvider provider = find(context, providerId);
        if (provider == null) return null;
        CustomProvider.ProviderModel model = null;
        if (provider.models != null) {
            for (CustomProvider.ProviderModel pm : provider.models) {
                if (pm.requestName != null && pm.requestName.equals(requestName)) {
                    model = pm;
                    break;
                }
            }
        }
        return new ResolvedCustomModel(provider, requestName, model);
    }

    public static synchronized List<CustomProvider> getAll(Context context) {
        List<CustomProvider> list = new ArrayList<>();
        if (context == null) return list;
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PROVIDERS, "{\"schema_version\":1,\"providers\":[]}");
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("providers");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    CustomProvider p = CustomProvider.fromJson(arr.optJSONObject(i));
                    if (p != null && !p.displayName.isEmpty() && !p.baseURL.isEmpty()) {
                        list.add(p);
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static synchronized void save(Context context, CustomProvider provider) {
        if (context == null || provider == null) return;
        List<CustomProvider> list = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (provider.id.equals(list.get(i).id)) {
                list.set(i, provider);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(provider);
        write(context, list);
    }

    public static synchronized void delete(Context context, String id) {
        if (context == null || id == null) return;
        List<CustomProvider> list = getAll(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (id.equals(list.get(i).id)) list.remove(i);
        }
        write(context, list);
    }

    public static synchronized CustomProvider find(Context context, String id) {
        if (id == null) return null;
        for (CustomProvider p : getAll(context)) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    private static void write(Context context, List<CustomProvider> list) {
        JSONArray arr = new JSONArray();
        for (CustomProvider p : list) arr.put(p.toJson());
        JSONObject root = new JSONObject();
        try {
            root.put("schema_version", SCHEMA_VERSION);
            root.put("providers", arr);
        } catch (Exception ignored) {}
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROVIDERS, root.toString()).apply();
    }
}

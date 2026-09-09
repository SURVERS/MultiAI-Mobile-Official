package app.multiai.mobile.utils;

import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

/** Compatibility envelope for APIs whose legacy image argument is a single string. */
public final class ImageAttachments {
    private ImageAttachments() {}
    public static List<String> unpack(String value) {
        List<String> images = new ArrayList<>();
        if (value == null || value.isEmpty()) return images;
        if (!value.startsWith("[")) { images.add(value); return images; }
        try {
            JSONArray array = new JSONArray(value);
            for (int i = 0; i < array.length(); i++) {
                String image = array.getString(i);
                if (!image.isEmpty()) images.add(image);
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid image attachment collection", error);
        }
        return images;
    }
    public static String pack(List<String> images) {
        if (images.isEmpty()) return null;
        return images.size() == 1 ? images.get(0) : new JSONArray(images).toString();
    }
    public static String append(String current, String image) {
        List<String> images = unpack(current);
        if (images.size() >= PhotoPicker.MAX_IMAGES) throw new IllegalArgumentException("Maximum 10 images");
        images.add(image);
        return pack(images);
    }
    public static String remove(String current, int index) {
        List<String> images = unpack(current);
        images.remove(index);
        return pack(images);
    }
}

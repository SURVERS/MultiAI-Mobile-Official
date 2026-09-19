package app.multiai.mobile.views;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.cardview.widget.CardView;
import app.multiai.mobile.R;
import app.multiai.mobile.utils.ImageAttachments;

public final class ImageAttachmentStrip {
    public interface Remove { void remove(int index); }
    private ImageAttachmentStrip() {}
    public static void render(View root, String payload, Remove remove) {
        render(root, payload, remove, null, null, null);
    }

    public static void render(View root, String payload, Remove remove, String fileName,
                              String fileContent, Remove removeFile) {
        ViewGroup host = (ViewGroup) root;
        View old = host.findViewWithTag("multi-image-strip");
        if (old != null) host.removeView(old);
        for (int id : new int[]{R.id.attachmentImageBox, R.id.attachmentFileBox, R.id.btnRemoveAttachment}) {
            View child = host.findViewById(id);
            if (child != null) child.setVisibility(View.GONE);
        }
        View legacyImage = host.findViewById(R.id.imageAttachment);
        if (legacyImage != null) legacyImage.setVisibility(View.GONE);
        java.util.List<String> images = ImageAttachments.unpack(payload);
        java.util.List<app.multiai.mobile.utils.FileAttachments.Entry> files =
                app.multiai.mobile.utils.FileAttachments.unpack(fileName, fileContent);
        host.setVisibility(images.isEmpty() && files.isEmpty() ? View.GONE : View.VISIBLE);
        if (images.isEmpty() && files.isEmpty()) return;
        Context ctx = host.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setTag("multi-image-strip");
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        scroll.addView(row);
        host.addView(scroll, new ViewGroup.LayoutParams(-1, (int)(104*density)));
        for (int i = 0; i < images.size(); i++) {
            final int index = i;
            FrameLayout tile = new FrameLayout(ctx);
            row.addView(tile, new LinearLayout.LayoutParams((int)(108*density), (int)(104*density)));
            CardView card = new CardView(ctx);
            card.setRadius(14*density);
            card.setCardElevation(0);
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams((int)(92*density), (int)(92*density));
            cardParams.topMargin = (int)(8*density);
            tile.addView(card, cardParams);
            ImageView preview = new ImageView(ctx);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setContentDescription(ctx.getString(R.string.lay_cd_attached_image) + " " + (i+1));
            String encoded = images.get(i);
            int comma = encoded.indexOf(','); // data URI prefix breaks strict Base64 decode
            byte[] bytes = android.util.Base64.decode(comma >= 0 ? encoded.substring(comma + 1) : encoded,
                    android.util.Base64.DEFAULT);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts));
            card.addView(preview, new ViewGroup.LayoutParams(-1, -1));
            FrameLayout button = new FrameLayout(ctx);
            FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams((int)(48*density), (int)(48*density), Gravity.TOP|Gravity.END);
            tile.addView(button, buttonParams);
            button.setContentDescription(ctx.getString(R.string.remove_attachment) + " " + (i+1));
            button.setFocusable(true);
            button.setVisibility(remove == null ? View.GONE : View.VISIBLE);
            button.setOnClickListener(v -> { if (remove != null) remove.remove(index); });
            ImageView cross = new ImageView(ctx);
            cross.setImageResource(R.drawable.ic_close);
            cross.setBackgroundResource(R.drawable.attachment_remove_badge);
            cross.setColorFilter(android.graphics.Color.WHITE);
            int padding = (int)(6*density);
            cross.setPadding(padding,padding,padding,padding);
            button.addView(cross, new FrameLayout.LayoutParams((int)(24*density), (int)(24*density), Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        }
        for (int i = 0; i < files.size(); i++) {
            final int index = i;
            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding((int)(12*density), 0, (int)(12*density), 0);
            row.addView(tile, new LinearLayout.LayoutParams((int)(152*density), (int)(104*density)));
            android.widget.TextView name = new android.widget.TextView(ctx);
            name.setText(files.get(i).name);
            name.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.icon));
            name.setTextSize(13);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            if (removeFile != null) {
                ImageView cross = new ImageView(ctx);
                cross.setImageResource(R.drawable.ic_close);
                cross.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.icon));
                cross.setPadding((int)(14*density), (int)(14*density), (int)(14*density), (int)(14*density));
                cross.setContentDescription(ctx.getString(R.string.remove_attachment) + " " + files.get(i).name);
                cross.setFocusable(true);
                cross.setOnClickListener(v -> removeFile.remove(index));
                tile.addView(cross, new LinearLayout.LayoutParams((int)(48*density), (int)(48*density)));
            }
        }
        // Плавное появление стрипа при добавлении/перерисовке вложений
        scroll.setAlpha(0f);
        scroll.setTranslationY(6*density);
        scroll.animate().alpha(1f).translationY(0f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
    }
}

package net.programmierecke.radiodroid2.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;

/**
 * Utility class for loading images using Glide
 * Replaces the deprecated Picasso implementation
 */
public class ImageLoader {

    /**
     * Interface for bitmap loading callbacks
     */
    public interface BitmapTarget {
        void onBitmapLoaded(Bitmap bitmap);
        void onBitmapFailed(Drawable errorDrawable);
        void onPrepareLoad(Drawable placeHolderDrawable);
    }

    /**
     * Load image into ImageView with standard options
     */
    public static void loadImage(Context context, String url, ImageView imageView) {
        Glide.with(context)
                .load(url)
                .apply(new RequestOptions()
                        .error(R.drawable.ic_launcher)
                        .transform(new CenterCrop()))
                .into(imageView);
    }

    /**
     * Load image into ImageView bypassing cache (for forced refresh)
     */
    public static void loadImageFresh(Context context, String url, ImageView imageView) {
        Glide.with(context)
                .load(url)
                .apply(new RequestOptions()
                        .error(R.drawable.ic_launcher)
                        .transform(new CenterCrop())
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true))
                .into(imageView);
    }

    /**
     * Load image into ImageView with transformations for radio station icons
     */
    public static void loadStationIcon(Context context, String url, ImageView imageView) {
        RequestOptions options = new RequestOptions()
                .error(R.drawable.ic_launcher)
                .transform(new CenterCrop());

        if (Utils.useCircularIcons(context)) {
            options = options.transform(new CircleCrop());
        } else {
            options = options.transform(new RoundedCorners(12));
        }

        Glide.with(context)
                .load(url)
                .apply(options)
                .into(imageView);
    }

    /**
     * Load image with specific size and transformations for media browser
     */
    public static void loadStationIconForBrowser(Context context, String url, int size, boolean useCircular, BitmapTarget target) {
        RequestOptions options = new RequestOptions()
                .error(R.drawable.ic_launcher)
                .override(size, size);

        if (useCircular) {
            options = options.transform(new CenterCrop(), new CircleCrop());
        } else {
            options = options.transform(new CenterCrop(), new RoundedCorners(12));
        }

        Glide.with(context)
                .asBitmap()
                .load(url)
                .apply(options)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        target.onBitmapLoaded(resource);
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        target.onBitmapFailed(errorDrawable);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        target.onPrepareLoad(placeholder);
                    }
                });
    }

    /**
     * Cancel image loading for a specific ImageView
     */
    public static void cancelRequest(Context context, ImageView imageView) {
        Glide.with(context).clear(imageView);
    }

    /**
     * Cancel image loading for a specific target
     */
    public static void cancelRequest(Context context, CustomTarget<?> target) {
        Glide.with(context).clear(target);
    }

    /**
     * Load station icon with offline-first strategy and fallback
     * Mimics Picasso's NetworkPolicy.OFFLINE behavior
     */
    public static void loadStationIconOfflineFirst(Context context, String url, ImageView imageView, Drawable placeholder) {
        final float px = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 70, 
                context.getResources().getDisplayMetrics());

        RequestOptions offlineOptions = new RequestOptions()
                .placeholder(placeholder)
                .override((int) px, (int) px)  // Force square aspect ratio
                .transform(new CenterCrop())
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .onlyRetrieveFromCache(true);

        RequestOptions fallbackOptions = new RequestOptions()
                .placeholder(placeholder)
                .override((int) px, (int) px)  // Force square aspect ratio
                .transform(new CenterCrop())
                .diskCacheStrategy(DiskCacheStrategy.ALL);

        // Create a fallback request using Glide's error() method
        com.bumptech.glide.RequestBuilder<Drawable> fallbackRequest = Glide.with(context)
                .load(url)
                .apply(fallbackOptions);

        // First try to load from cache only, with network fallback on error
        Glide.with(context)
                .load(url)
                .apply(offlineOptions)
                .error(fallbackRequest)
                .into(imageView);
    }
}
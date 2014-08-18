package com.mopub.nativeads;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.inmobi.commons.InMobi;
import com.inmobi.monetization.IMErrorCode;
import com.inmobi.monetization.IMNative;
import com.inmobi.monetization.IMNativeListener;
import com.mopub.common.util.MoPubLog;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mopub.common.util.Json.getJsonValue;
import static com.mopub.common.util.Numbers.parseDouble;

/*
 * Tested with InMobi SDK 4.4.1
 */
class InMobiNative extends CustomEventNative implements IMNativeListener {
    private static final String APP_ID_KEY = "app_id";

    private Context mContext;
    private CustomEventNativeListener mCustomEventNativeListener;

    // CustomEventNative implementation
    @Override
    protected void loadNativeAd(final Context context,
            final CustomEventNativeListener customEventNativeListener,
            final Map<String, Object> localExtras,
            final Map<String, String> serverExtras) {

        mContext = context;

        if (!(context instanceof Activity)) {
            customEventNativeListener.onNativeAdFailed(NativeErrorCode.NATIVE_ADAPTER_CONFIGURATION_ERROR);
            return;
        }
        final Activity activity = (Activity) context;

        final String appId;
        if (extrasAreValid(serverExtras)) {
            appId = serverExtras.get(APP_ID_KEY);
        } else {
            customEventNativeListener.onNativeAdFailed(NativeErrorCode.NATIVE_ADAPTER_CONFIGURATION_ERROR);
            return;
        }

        mCustomEventNativeListener = customEventNativeListener;

        InMobi.initialize(activity, appId);
        final IMNative imNative = new IMNative(this);
        imNative.loadAd();
    }

    // IMNativeListener implementation
    @Override
    public void onNativeRequestSucceeded(final IMNative imNative) {
        if (imNative == null) {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.NETWORK_INVALID_STATE);
            return;
        }

        final InMobiForwardingNativeAd inMobiForwardingNativeAd;
        try {
            inMobiForwardingNativeAd = new InMobiForwardingNativeAd(imNative);
        } catch (IllegalArgumentException e) {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.UNSPECIFIED);
            return;
        } catch (JSONException e) {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.INVALID_JSON);
            return;
        }

        final List<String> imageUrls = new ArrayList<String>();
        final String mainImageUrl = inMobiForwardingNativeAd.getMainImageUrl();
        if (mainImageUrl != null) {
            imageUrls.add(mainImageUrl);
        }
        final String iconUrl = inMobiForwardingNativeAd.getIconImageUrl();
        if (iconUrl != null) {
            imageUrls.add(iconUrl);
        }

        preCacheImages(mContext, imageUrls, new ImageListener() {
            @Override
            public void onImagesCached() {
                mCustomEventNativeListener.onNativeAdLoaded(inMobiForwardingNativeAd);
            }

            @Override
            public void onImagesFailedToCache(NativeErrorCode errorCode) {
                mCustomEventNativeListener.onNativeAdFailed(errorCode);
            }
        });
    }

    @Override
    public void onNativeRequestFailed(final IMErrorCode errorCode) {
        if (errorCode == IMErrorCode.INVALID_REQUEST) {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.NETWORK_INVALID_REQUEST);
        } else if (errorCode == IMErrorCode.INTERNAL_ERROR || errorCode == IMErrorCode.NETWORK_ERROR) {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.NETWORK_INVALID_STATE);
        } else if (errorCode == IMErrorCode.NO_FILL) {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.NETWORK_NO_FILL);
        } else {
            mCustomEventNativeListener.onNativeAdFailed(NativeErrorCode.UNSPECIFIED);
        }
    }

    private boolean extrasAreValid(final Map<String, String> serverExtras) {
        final String placementId = serverExtras.get(APP_ID_KEY);
        return (placementId != null && placementId.length() > 0);
    }

    static class InMobiForwardingNativeAd extends BaseForwardingNativeAd {
        static final int IMPRESSION_MIN_TIME_VIEWED = 0;

        // Modifiable keys
        static final String TITLE = "title";
        static final String DESCRIPTION = "description";
        static final String SCREENSHOTS = "screenshots";
        static final String ICON = "icon";
        static final String LANDING_URL = "landing_url";
        static final String CTA = "cta";
        static final String RATING = "rating";

        // Constant keys
        static final String URL = "url";

        private final IMNative mImNative;

        InMobiForwardingNativeAd(final IMNative imNative) throws IllegalArgumentException, JSONException {
            if (imNative == null) {
                throw new IllegalArgumentException("InMobi Native Ad cannot be null");
            }

            mImNative = imNative;

            final JSONTokener jsonTokener = new JSONTokener(mImNative.getContent());
            final JSONObject jsonObject = new JSONObject(jsonTokener);

            setTitle(getJsonValue(jsonObject, TITLE, String.class));
            setText(getJsonValue(jsonObject, DESCRIPTION, String.class));

            final JSONObject screenShotJsonObject = getJsonValue(jsonObject, SCREENSHOTS, JSONObject.class);
            if (screenShotJsonObject != null) {
                setMainImageUrl(getJsonValue(screenShotJsonObject, URL, String.class));
            }

            final JSONObject iconJsonObject = getJsonValue(jsonObject, ICON, JSONObject.class);
            if (iconJsonObject != null) {
                setIconImageUrl(getJsonValue(iconJsonObject, URL, String.class));
            }

            setClickDestinationUrl(getJsonValue(jsonObject, LANDING_URL, String.class));
            setCallToAction(getJsonValue(jsonObject, CTA, String.class));

            try {
                setStarRating(parseDouble(jsonObject.opt(RATING)));
            } catch (ClassCastException e) {
                MoPubLog.d("Unable to set invalid star rating for InMobi Native.");
            }
            setImpressionMinTimeViewed(IMPRESSION_MIN_TIME_VIEWED);
        }

        @Override
        public void prepareImpression(final View view) {
            if (view != null && view instanceof ViewGroup) {
                mImNative.attachToView((ViewGroup) view);
            } else if (view != null && view.getParent() instanceof ViewGroup) {
                mImNative.attachToView((ViewGroup) view.getParent());
            } else {
                MoPubLog.e("InMobi did not receive ViewGroup to attachToView, unable to record impressions");
            }
        }

        @Override
        public void handleClick(final View view) {
            mImNative.handleClick(null);
        }

        @Override
        public void destroy() {
            mImNative.detachFromView();
        }
    }
}

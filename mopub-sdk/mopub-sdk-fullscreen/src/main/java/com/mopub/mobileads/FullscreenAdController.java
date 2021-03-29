// Copyright 2018-2021 Twitter, Inc.
// Licensed under the MoPub SDK License Agreement
// https://www.mopub.com/legal/sdk-license-agreement/

package com.mopub.mobileads;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.mopub.common.CloseableLayout;
import com.mopub.common.CreativeOrientation;
import com.mopub.common.FullAdType;
import com.mopub.common.Preconditions;
import com.mopub.common.UrlAction;
import com.mopub.common.UrlHandler;
import com.mopub.common.logging.MoPubLog;
import com.mopub.common.util.AsyncTasks;
import com.mopub.common.util.DeviceUtils;
import com.mopub.common.util.Dips;
import com.mopub.common.util.Intents;
import com.mopub.mobileads.factories.HtmlControllerFactory;
import com.mopub.mobileads.resource.DrawableConstants;
import com.mopub.mraid.MraidController;
import com.mopub.mraid.PlacementType;
import com.mopub.mraid.WebViewDebugListener;
import com.mopub.network.Networking;
import com.mopub.network.TrackingRequest;
import com.mopub.volley.VolleyError;
import com.mopub.volley.toolbox.ImageLoader;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.Set;

import static com.mopub.common.IntentActions.ACTION_FULLSCREEN_CLICK;
import static com.mopub.common.IntentActions.ACTION_FULLSCREEN_DISMISS;
import static com.mopub.common.IntentActions.ACTION_FULLSCREEN_FAIL;
import static com.mopub.common.IntentActions.ACTION_REWARDED_AD_COMPLETE;
import static com.mopub.common.MoPubBrowser.MOPUB_BROWSER_REQUEST_CODE;
import static com.mopub.common.logging.MoPubLog.SdkLogEvent.CUSTOM;
import static com.mopub.common.util.JavaScriptWebViewCallbacks.WEB_VIEW_DID_APPEAR;
import static com.mopub.common.util.JavaScriptWebViewCallbacks.WEB_VIEW_DID_CLOSE;
import static com.mopub.mobileads.AdData.DEFAULT_DURATION_FOR_CLOSE_BUTTON_MILLIS;
import static com.mopub.mobileads.AdData.DEFAULT_DURATION_FOR_REWARDED_IMAGE_CLOSE_BUTTON_MILLIS;
import static com.mopub.mobileads.AdData.MILLIS_IN_SECOND;
import static com.mopub.mobileads.BaseBroadcastReceiver.broadcastAction;

public class FullscreenAdController implements BaseVideoViewController.BaseVideoViewControllerListener,
        MraidController.UseCustomCloseListener {

    static final String IMAGE_KEY = "image";
    @VisibleForTesting
    static final String CLICK_DESTINATION_KEY = "clk";
    @VisibleForTesting
    static final String WIDTH_KEY = "w";
    @VisibleForTesting
    static final String HEIGHT_KEY = "h";
    private final static EnumSet<UrlAction> SUPPORTED_URL_ACTIONS = EnumSet.of(
            UrlAction.IGNORE_ABOUT_SCHEME,
            UrlAction.HANDLE_PHONE_SCHEME,
            UrlAction.OPEN_APP_MARKET,
            UrlAction.OPEN_NATIVE_BROWSER,
            UrlAction.OPEN_IN_APP_BROWSER,
            UrlAction.HANDLE_SHARE_TWEET,
            UrlAction.FOLLOW_DEEP_LINK_WITH_FALLBACK,
            UrlAction.FOLLOW_DEEP_LINK);

    @NonNull
    private final Activity mActivity;
    @Nullable
    private BaseVideoViewController mVideoViewController;
    @NonNull
    private final MoPubWebViewController mMoPubWebViewController;
    @NonNull
    private final AdData mAdData;
    @NonNull
    private ControllerState mState = ControllerState.MRAID;
    @Nullable
    private WebViewDebugListener mDebugListener;
    @Nullable
    private CloseableLayout mCloseableLayout;
    @Nullable
    private RadialCountdownWidget mRadialCountdownWidget;
    @Nullable
    private CloseButtonCountdownRunnable mCountdownRunnable;
    @Nullable
    private VastCompanionAdConfig mSelectedVastCompanionAdConfig;
    @Nullable
    private ImageView mImageView;
    @Nullable
    private VideoCtaButtonWidget mVideoCtaButtonWidget;
    @Nullable
    private VastVideoBlurLastVideoFrameTask mBlurLastVideoFrameTask;
    @Nullable
    private String mImageClickDestinationUrl;
    private int mCurrentElapsedTimeMillis;
    private int mShowCloseButtonDelayMillis;
    private boolean mShowCloseButtonEventFired;
    private boolean mIsCalibrationDone;
    private boolean mRewardedCompletionFired;
    private int mVideoTimeElapsed;

    @VisibleForTesting
    enum ControllerState {
        VIDEO,
        MRAID,
        HTML,
        IMAGE
    }

    public FullscreenAdController(@NonNull final Activity activity,
                                  @Nullable final Bundle savedInstanceState,
                                  @NonNull final Intent intent,
                                  @NonNull final AdData adData) {
        mActivity = activity;
        mAdData = adData;

        final WebViewCacheService.Config config = WebViewCacheService.popWebViewConfig(adData.getBroadcastIdentifier());
        if (config != null && config.getController() != null) {
            mMoPubWebViewController = config.getController();
        } else if ("html".equals(adData.getAdType())) {
            mMoPubWebViewController = HtmlControllerFactory.create(activity,
                    adData.getDspCreativeId());
        } else {
            // If we hit this, then we assume this is MRAID since it isn't HTML
            mMoPubWebViewController = new MraidController(activity,
                    adData.getDspCreativeId(),
                    PlacementType.INTERSTITIAL,
                    mAdData.getAllowCustomClose());
        }

        final String htmlData = adData.getAdPayload();
        if (TextUtils.isEmpty(htmlData)) {
            MoPubLog.log(CUSTOM, "MoPubFullscreenActivity received an empty HTML body. Finishing the activity.");
            activity.finish();
            return;
        }

        if (mMoPubWebViewController instanceof MraidController) {
            ((MraidController) mMoPubWebViewController).setUseCustomCloseListener(this);
        }
        mMoPubWebViewController.setDebugListener(mDebugListener);
        mMoPubWebViewController.setMoPubWebViewListener(new BaseHtmlWebView.BaseWebViewListener() {
            @Override
            public void onLoaded(View view) {
                if (ControllerState.HTML.equals(mState) || ControllerState.MRAID.equals(mState)) {
                    mMoPubWebViewController.loadJavascript(WEB_VIEW_DID_APPEAR.getJavascript());
                }
            }

            @Override
            public void onFailedToLoad(MoPubErrorCode errorCode) {
                /* NO-OP. Loading has already completed if we're here */
            }

            @Override
            public void onFailed() {
                MoPubLog.log(CUSTOM, "FullscreenAdController failed to load. Finishing MoPubFullscreenActivity.");
                broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_FAIL);
                activity.finish();
            }

            @Override
            public void onRenderProcessGone(@NonNull final MoPubErrorCode errorCode) {
                MoPubLog.log(CUSTOM, "Finishing the activity due to a render process gone problem: " + errorCode);
                broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_FAIL);
                activity.finish();
            }

            @Override
            public void onClicked() {
                onAdClicked(activity, adData);
            }

            public void onClose() {
                broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_DISMISS);
                mMoPubWebViewController.loadJavascript(WEB_VIEW_DID_CLOSE.getJavascript());
                activity.finish();
            }

            @Override
            public void onExpand() {
                // No-op. The interstitial is always expanded.
            }

            @Override
            public void onResize(final boolean toOriginalSize) {
                // No-op. The interstitial is always expanded.
            }
        });

        mCloseableLayout = new CloseableLayout(mActivity);

        if (FullAdType.VAST.equals(mAdData.getFullAdType())) {
            mVideoViewController = createVideoViewController(activity, savedInstanceState, intent, adData.getBroadcastIdentifier());
            mState = ControllerState.VIDEO;
            mVideoViewController.onCreate();
            return;
        } else if (FullAdType.JSON.equals(mAdData.getFullAdType())) {
            mState = ControllerState.IMAGE;
            final JSONObject imageData;
            final String imageUrl;
            final int imageWidth, imageHeight;
            try {
                imageData = new JSONObject(mAdData.getAdPayload());
                imageUrl = imageData.getString(IMAGE_KEY);
                imageWidth = imageData.getInt(WIDTH_KEY);
                imageHeight = imageData.getInt(HEIGHT_KEY);
                mImageClickDestinationUrl = imageData.optString(CLICK_DESTINATION_KEY);
            } catch (JSONException e) {
                MoPubLog.log(CUSTOM, "Unable to load image into fullscreen container.");
                broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_FAIL);
                mActivity.finish();
                return;
            }
            mImageView = new ImageView(mActivity);
            Networking.getImageLoader(mActivity).get(imageUrl, new ImageLoader.ImageListener() {
                @Override
                public void onResponse(ImageLoader.ImageContainer imageContainer, boolean b) {
                    Bitmap bitmap = imageContainer.getBitmap();
                    if (mImageView != null && bitmap != null) {
                        mImageView.setAdjustViewBounds(true);
                        mImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                        // Scale up the image as if the device had a DPI of 160
                        bitmap.setDensity(DisplayMetrics.DENSITY_MEDIUM);
                        mImageView.setImageBitmap(bitmap);
                    } else {
                        MoPubLog.log(CUSTOM, String.format("%s returned null bitmap", imageUrl));
                    }
                }

                @Override
                public void onErrorResponse(VolleyError volleyError) {
                    MoPubLog.log(CUSTOM, String.format("Failed to retrieve image at %s", imageUrl));
                }
            }, imageWidth, imageHeight, ImageView.ScaleType.CENTER_INSIDE);

            final FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            layoutParams.gravity = Gravity.CENTER;
            mImageView.setLayoutParams(layoutParams);
            mCloseableLayout.addView(mImageView);
            mCloseableLayout.setOnCloseListener(() -> {
                destroy();
                mActivity.finish();
            });
            if (mAdData.isRewarded()) {
                mCloseableLayout.setCloseAlwaysInteractable(false);
                mCloseableLayout.setCloseVisible(false);
            }
            mActivity.setContentView(mCloseableLayout);
        } else {
            if (config == null || config.getController() == null) {
                mMoPubWebViewController.fillContent(htmlData,
                        adData.getViewabilityVendors(),
                        webView -> { });
            }

            if ("html".equals(mAdData.getAdType())) {
                mState = ControllerState.HTML;
            } else {
                mState = ControllerState.MRAID;
            }

            mCloseableLayout.setOnCloseListener(() -> {
                destroy();
                mActivity.finish();
            });
            mCloseableLayout.addView(mMoPubWebViewController.getAdContainer(),
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (mAdData.isRewarded()) {
                mCloseableLayout.setCloseAlwaysInteractable(false);
                mCloseableLayout.setCloseVisible(false);
            }
            mActivity.setContentView(mCloseableLayout);
            mMoPubWebViewController.onShow(mActivity);
        }

        if (ControllerState.HTML.equals(mState) || ControllerState.IMAGE.equals(mState)) {
            // Default to device orientation
            CreativeOrientation requestedOrientation = CreativeOrientation.DEVICE;
            if (adData.getOrientation() != null) {
                requestedOrientation = adData.getOrientation();
            }
            DeviceUtils.lockOrientation(activity, requestedOrientation);
        }

        if (mAdData.isRewarded()) {
            addRadialCountdownWidget(activity, View.INVISIBLE);
            if (ControllerState.IMAGE.equals(mState)) {
                mShowCloseButtonDelayMillis = adData.getRewardedDurationSeconds() >= 0 ?
                        adData.getRewardedDurationSeconds() * MILLIS_IN_SECOND :
                        DEFAULT_DURATION_FOR_REWARDED_IMAGE_CLOSE_BUTTON_MILLIS;
            } else {
                mShowCloseButtonDelayMillis = adData.getRewardedDurationSeconds() >= 0 ?
                        adData.getRewardedDurationSeconds() * MILLIS_IN_SECOND :
                        DEFAULT_DURATION_FOR_CLOSE_BUTTON_MILLIS;
            }
            mRadialCountdownWidget.calibrateAndMakeVisible(mShowCloseButtonDelayMillis);
            mIsCalibrationDone = true;
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            mCountdownRunnable = new CloseButtonCountdownRunnable(this, mainHandler);
        } else {
            showCloseButton();
        }
    }

    @VisibleForTesting
    BaseVideoViewController createVideoViewController(Activity activity, Bundle savedInstanceState, Intent intent, Long broadcastIdentifier) throws IllegalStateException {
        return new VastVideoViewController(activity, intent.getExtras(), savedInstanceState, broadcastIdentifier, this);
    }

    // Start BaseVideoViewControllerListener implementation

    @Override
    public void onSetContentView(View view) {
        mActivity.setContentView(view);
    }

    @Override
    public void onSetRequestedOrientation(int requestedOrientation) {
        mActivity.setRequestedOrientation(requestedOrientation);
    }

    @Override
    public void onVideoFinish(final int timeElapsed) {
        if (mCloseableLayout == null || mSelectedVastCompanionAdConfig == null) {
            destroy();
            mActivity.finish();
            return;
        }

        mVideoTimeElapsed = timeElapsed;

        if (mVideoViewController != null) {
            mVideoViewController.onPause();
            mVideoViewController.onDestroy();
            mVideoViewController = null;
        }

        // remove mImageView first to prevent IllegalStateException when added to relativeLayout
        if (mImageView != null) {
            final ViewGroup imageViewParent = (ViewGroup) mImageView.getParent();
            if (imageViewParent != null) {
                imageViewParent.removeView(mImageView);
            }
        }

        mCloseableLayout.removeAllViews();
        mCloseableLayout.setOnCloseListener(() -> {
            destroy();
            mActivity.finish();
        });
        final VastResource vastResource = mSelectedVastCompanionAdConfig.getVastResource();
        if (VastResource.Type.STATIC_RESOURCE.equals(vastResource.getType()) &&
                VastResource.CreativeType.IMAGE.equals(vastResource.getCreativeType()) ||
                VastResource.Type.BLURRED_LAST_FRAME.equals(vastResource.getType())) {
            mState = ControllerState.IMAGE;
            if (mImageView == null) {
                MoPubLog.log(CUSTOM, "Companion image null. Skipping.");
                destroy();
                mActivity.finish();
                return;
            }
            final RelativeLayout relativeLayout = new RelativeLayout(mActivity);
            RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT);
            mImageView.setLayoutParams(layoutParams);
            relativeLayout.addView(mImageView);
            if (mVideoCtaButtonWidget != null) {
                relativeLayout.addView(mVideoCtaButtonWidget);
            }
            mCloseableLayout.addView(relativeLayout);
        } else {
            mState = ControllerState.MRAID;
            mCloseableLayout.addView(mMoPubWebViewController.getAdContainer(),
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        if (mAdData.isRewarded()) {
            mCloseableLayout.setCloseAlwaysInteractable(false);
            mCloseableLayout.setCloseVisible(false);
        }
        mActivity.setContentView(mCloseableLayout);
        mMoPubWebViewController.onShow(mActivity);

        if (mAdData.isRewarded()) {
            mShowCloseButtonDelayMillis = mAdData.getRewardedDurationSeconds() >= 0 ?
                    mAdData.getRewardedDurationSeconds() * MILLIS_IN_SECOND :
                    DEFAULT_DURATION_FOR_CLOSE_BUTTON_MILLIS;
            if (timeElapsed >= mShowCloseButtonDelayMillis ||
                    VastResource.Type.BLURRED_LAST_FRAME.equals(
                            mSelectedVastCompanionAdConfig.getVastResource().getType())) {
                mCloseableLayout.setCloseAlwaysInteractable(true);
                showCloseButton();
            } else {
                addRadialCountdownWidget(mActivity, View.INVISIBLE);
                mRadialCountdownWidget.calibrateAndMakeVisible(mShowCloseButtonDelayMillis);
                mRadialCountdownWidget.updateCountdownProgress(mShowCloseButtonDelayMillis, timeElapsed);
                mIsCalibrationDone = true;
                final Handler mainHandler = new Handler(Looper.getMainLooper());
                mCountdownRunnable = new CloseButtonCountdownRunnable(this, mainHandler);
                mCountdownRunnable.mCurrentElapsedTimeMillis = timeElapsed;
                startRunnables();
            }
        } else {
            mCloseableLayout.setCloseAlwaysInteractable(true);
            showCloseButton();
        }

        mSelectedVastCompanionAdConfig.handleImpression(mActivity, timeElapsed);
    }

    @Override
    public void onStartActivityForResult(Class<? extends Activity> clazz, int requestCode, Bundle extras) {
        if (clazz == null) {
            return;
        }

        final Intent intent = Intents.getStartActivityIntent(mActivity, clazz, extras);

        try {
            mActivity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            MoPubLog.log(CUSTOM, "Activity " + clazz.getName() + " not found. Did you declare it in your AndroidManifest.xml?");
        }
    }

    @Override
    public void onCompanionAdsReady(@NonNull final Set<VastCompanionAdConfig> vastCompanionAdConfigs,
                                   final int videoDurationMs) {
        Preconditions.checkNotNull(vastCompanionAdConfigs);

        if (mCloseableLayout == null) {
            MoPubLog.log(CUSTOM, "CloseableLayout is null. This should not happen.");
        }

        final DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
        final int widthPixels = displayMetrics.widthPixels;
        final int heightPixels = displayMetrics.heightPixels;
        final int widthDp = (int) (widthPixels / displayMetrics.density);
        final int heightDp = (int) (heightPixels / displayMetrics.density);
        VastCompanionAdConfig bestCompanionAdConfig = null;
        for (final VastCompanionAdConfig vastCompanionAdConfig : vastCompanionAdConfigs) {
            if (vastCompanionAdConfig == null) {
                continue;
            }
            if (bestCompanionAdConfig == null ||
                    vastCompanionAdConfig.calculateScore(widthDp, heightDp) >
                            bestCompanionAdConfig.calculateScore(widthDp, heightDp)) {
                bestCompanionAdConfig = vastCompanionAdConfig;
            }
        }
        mSelectedVastCompanionAdConfig = bestCompanionAdConfig;
        if (mSelectedVastCompanionAdConfig == null) {
            return;
        }
        final VastResource vastResource = mSelectedVastCompanionAdConfig.getVastResource();
        final String htmlResourceValue = vastResource.getHtmlResourceValue();
        if (TextUtils.isEmpty(htmlResourceValue)) {
            return;
        }
        if (VastResource.Type.STATIC_RESOURCE.equals(vastResource.getType()) &&
                VastResource.CreativeType.IMAGE.equals(vastResource.getCreativeType())) {
            mImageView = new ImageView(mActivity);
            Networking.getImageLoader(mActivity).get(vastResource.getResource(), new ImageLoader.ImageListener() {
                @Override
                public void onResponse(ImageLoader.ImageContainer imageContainer, boolean b) {
                    Bitmap bitmap = imageContainer.getBitmap();
                    if (mImageView != null && bitmap != null) {
                        mImageView.setAdjustViewBounds(true);
                        mImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                        // Scale up the image as if the device had a DPI of 160
                        bitmap.setDensity(DisplayMetrics.DENSITY_MEDIUM);
                        mImageView.setImageBitmap(bitmap);
                    } else {
                        MoPubLog.log(CUSTOM, String.format("%s returned null bitmap", vastResource.getResource()));
                    }
                }

                @Override
                public void onErrorResponse(VolleyError volleyError) {
                    MoPubLog.log(CUSTOM, String.format("Failed to retrieve image at %s",
                            vastResource.getResource()));
                }
            }, mSelectedVastCompanionAdConfig.getWidth(), mSelectedVastCompanionAdConfig.getHeight(), ImageView.ScaleType.CENTER_INSIDE);
            mImageView.setOnClickListener(view -> onAdClicked(mActivity, mAdData));
        } else if (VastResource.Type.BLURRED_LAST_FRAME.equals(vastResource.getType())) {
            mImageView = new ImageView(mActivity);
            mImageView.setOnClickListener(view -> onAdClicked(mActivity, mAdData));
            mBlurLastVideoFrameTask = new VastVideoBlurLastVideoFrameTask(
                    new MediaMetadataRetriever(),
                    mImageView,
                    videoDurationMs);
            AsyncTasks.safeExecuteOnExecutor(mBlurLastVideoFrameTask, vastResource.getResource());
            if (!TextUtils.isEmpty(mSelectedVastCompanionAdConfig.getClickThroughUrl())) {
                mVideoCtaButtonWidget = new VideoCtaButtonWidget(mActivity, false, true);
                final String customCtaText = mSelectedVastCompanionAdConfig.getCustomCtaText();
                if (!TextUtils.isEmpty(customCtaText)) {
                    mVideoCtaButtonWidget.updateCtaText(customCtaText);
                }
                mVideoCtaButtonWidget.notifyVideoComplete();
                mVideoCtaButtonWidget.setOnClickListener(view -> onAdClicked(mActivity, mAdData));
            }
        } else {
            mMoPubWebViewController.fillContent(htmlResourceValue, null, null);
        }

    }

    // End BaseVideoViewControllerListener implementation

    // MraidController.UseCustomCloseListener
    @Override
    public void useCustomCloseChanged(final boolean useCustomClose) {
        if (mCloseableLayout == null) {
            return;
        }
        if (useCustomClose && !mAdData.isRewarded()) {
            mCloseableLayout.setCloseVisible(false);
            return;
        }
        if (mShowCloseButtonEventFired) {
            mCloseableLayout.setCloseVisible(true);
        }

    }
    // End MraidController.UseCustomCloseListener implementation

    public void pause() {
        if (mVideoViewController != null) {
            mVideoViewController.onPause();
        }
        if (ControllerState.HTML.equals(mState) || ControllerState.MRAID.equals(mState)) {
            mMoPubWebViewController.pause(false);
        }
        stopRunnables();
    }

    public void resume() {
        if (mVideoViewController != null) {
            mVideoViewController.onResume();
        }
        if (ControllerState.HTML.equals(mState) || ControllerState.MRAID.equals(mState)) {
            mMoPubWebViewController.resume();
        }
        startRunnables();
    }

    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (mVideoViewController != null) {
            mVideoViewController.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void destroy() {
        mMoPubWebViewController.destroy();
        if (mVideoViewController != null) {
            mVideoViewController.onDestroy();
            mVideoViewController = null;
        }
        stopRunnables();
        if (mBlurLastVideoFrameTask != null) {
            mBlurLastVideoFrameTask.cancel(true);
        }
        broadcastAction(mActivity, mAdData.getBroadcastIdentifier(), ACTION_FULLSCREEN_DISMISS);
    }

    boolean backButtonEnabled() {
        if (ControllerState.VIDEO.equals(mState) && mVideoViewController != null) {
            return mVideoViewController.backButtonEnabled();
        } else if (ControllerState.MRAID.equals(mState) || ControllerState.IMAGE.equals(mState)) {
            return mShowCloseButtonEventFired;
        }
        return true;
    }

    private boolean isCloseable() {
        return !mShowCloseButtonEventFired && mCurrentElapsedTimeMillis >= mShowCloseButtonDelayMillis;
    }

    @VisibleForTesting
    void showCloseButton() {
        mShowCloseButtonEventFired = true;

        if (mRadialCountdownWidget != null) {
            mRadialCountdownWidget.setVisibility(View.GONE);
        }
        if (mCloseableLayout != null) {
            mCloseableLayout.setCloseVisible(true);
        }

        if (!mRewardedCompletionFired && mAdData.isRewarded()) {
            broadcastAction(mActivity, mAdData.getBroadcastIdentifier(), ACTION_REWARDED_AD_COMPLETE);
            mRewardedCompletionFired = true;
        }

        if (mImageView != null) {
            mImageView.setOnClickListener(view -> {
                onAdClicked(mActivity, mAdData);
            });
        }
    }

    private void updateCountdown(int currentElapsedTimeMillis) {
        mCurrentElapsedTimeMillis = currentElapsedTimeMillis;
        if (mIsCalibrationDone && mRadialCountdownWidget != null) {
            mRadialCountdownWidget.updateCountdownProgress(mShowCloseButtonDelayMillis,
                    mCurrentElapsedTimeMillis);
        }
    }

    private void startRunnables() {
        if (mCountdownRunnable != null) {
            mCountdownRunnable.startRepeating(AdData.COUNTDOWN_UPDATE_INTERVAL_MILLIS);
        }
    }

    private void stopRunnables() {
        if (mCountdownRunnable != null) {
            mCountdownRunnable.stop();
        }
    }

    private void addRadialCountdownWidget(@NonNull final Context context, int initialVisibility) {
        mRadialCountdownWidget = new RadialCountdownWidget(context);
        mRadialCountdownWidget.setVisibility(initialVisibility);

        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) mRadialCountdownWidget.getLayoutParams();
        final int widgetWidth = lp.width;
        final int widgetHeight = lp.height;

        FrameLayout.LayoutParams widgetLayoutParams =
                new FrameLayout.LayoutParams(widgetWidth, widgetHeight);
        widgetLayoutParams.rightMargin =
                Dips.dipsToIntPixels(DrawableConstants.CloseButton.EDGE_MARGIN, context);
        widgetLayoutParams.topMargin =
                Dips.dipsToIntPixels(DrawableConstants.CloseButton.EDGE_MARGIN, context);
        widgetLayoutParams.gravity = Gravity.TOP | Gravity.RIGHT;
        mCloseableLayout.addView(mRadialCountdownWidget, widgetLayoutParams);
    }

    void onAdClicked(@NonNull final Activity activity, @NonNull final AdData adData) {
        if (mSelectedVastCompanionAdConfig != null &&
                !TextUtils.isEmpty(mSelectedVastCompanionAdConfig.getClickThroughUrl()) &&
                ControllerState.IMAGE.equals(mState)) {
            broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_CLICK);
            TrackingRequest.makeVastTrackingHttpRequest(
                    mSelectedVastCompanionAdConfig.getClickTrackers(),
                    null,
                    mVideoTimeElapsed,
                    null,
                    activity
            );
            mSelectedVastCompanionAdConfig.handleClick(
                    activity,
                    MOPUB_BROWSER_REQUEST_CODE,
                    null,
                    adData.getDspCreativeId()
            );
        } else if (mSelectedVastCompanionAdConfig != null && ControllerState.MRAID.equals(mState)) {
            broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_CLICK);
            TrackingRequest.makeVastTrackingHttpRequest(
                    mSelectedVastCompanionAdConfig.getClickTrackers(),
                    null,
                    mVideoTimeElapsed,
                    null,
                    activity
            );
        } else if (mSelectedVastCompanionAdConfig == null &&
                ControllerState.IMAGE.equals(mState) &&
                mImageClickDestinationUrl != null &&
                !TextUtils.isEmpty(mImageClickDestinationUrl)) {
            broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_CLICK);
            new UrlHandler.Builder()
                    .withDspCreativeId(mAdData.getDspCreativeId())
                    .withSupportedUrlActions(SUPPORTED_URL_ACTIONS)
                    .build().handleUrl(mActivity, mImageClickDestinationUrl);
        } else if (mSelectedVastCompanionAdConfig == null &&
                (ControllerState.MRAID.equals(mState) || ControllerState.HTML.equals(mState))) {
            broadcastAction(activity, adData.getBroadcastIdentifier(), ACTION_FULLSCREEN_CLICK);
        }
    }

    static class CloseButtonCountdownRunnable extends RepeatingHandlerRunnable {
        @NonNull
        private final FullscreenAdController mController;
        private int mCurrentElapsedTimeMillis;

        private CloseButtonCountdownRunnable(@NonNull final FullscreenAdController controller,
                                             @NonNull final Handler handler) {
            super(handler);
            Preconditions.checkNotNull(handler);
            Preconditions.checkNotNull(controller);

            mController = controller;
        }

        @Override
        public void doWork() {
            mCurrentElapsedTimeMillis += mUpdateIntervalMillis;
            mController.updateCountdown(mCurrentElapsedTimeMillis);

            if (mController.isCloseable()) {
                mController.showCloseButton();
            }
        }

        @Deprecated
        @VisibleForTesting
        int getCurrentElapsedTimeMillis() {
            return mCurrentElapsedTimeMillis;
        }
    }

    @Deprecated
    @VisibleForTesting
    void setDebugListener(@Nullable final WebViewDebugListener debugListener) {
        mDebugListener = debugListener;
        mMoPubWebViewController.setDebugListener(mDebugListener);
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    MoPubWebViewController getMoPubWebViewController() {
        return mMoPubWebViewController;
    }

    @Deprecated
    @VisibleForTesting
    int getShowCloseButtonDelayMillis() {
        return mShowCloseButtonDelayMillis;
    }

    @Deprecated
    @VisibleForTesting
    @NonNull
    CloseableLayout getCloseableLayout() {
        return mCloseableLayout;
    }

    @Deprecated
    @VisibleForTesting
    void setCloseableLayout(@Nullable final CloseableLayout closeableLayout) {
        mCloseableLayout = closeableLayout;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    CloseButtonCountdownRunnable getCountdownRunnable() {
        return mCountdownRunnable;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    RadialCountdownWidget getRadialCountdownWidget() {
        return mRadialCountdownWidget;
    }

    @Deprecated
    @VisibleForTesting
    boolean isCalibrationDone() {
        return mIsCalibrationDone;
    }

    @Deprecated
    @VisibleForTesting
    boolean isRewarded() {
        return mRewardedCompletionFired;
    }

    @Deprecated
    @VisibleForTesting
    boolean isShowCloseButtonEventFired() {
        return mShowCloseButtonEventFired;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    ImageView getImageView() {
        return mImageView;
    }

    @Deprecated
    @VisibleForTesting
    void setBlurLastVideoFrameTask(@Nullable final VastVideoBlurLastVideoFrameTask blurLastVideoFrameTask) {
        mBlurLastVideoFrameTask = blurLastVideoFrameTask;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    VastVideoBlurLastVideoFrameTask getBlurLastVideoFrameTask() {
        return mBlurLastVideoFrameTask;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    String getImageClickDestinationUrl() {
        return mImageClickDestinationUrl;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    VideoCtaButtonWidget getVideoCtaButtonWidget() {
        return mVideoCtaButtonWidget;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    VastCompanionAdConfig getSelectedVastCompanionAdConfig() {
        return mSelectedVastCompanionAdConfig;
    }

    @Deprecated
    @VisibleForTesting
    @Nullable
    BaseVideoViewController getVideoViewController() {
        return mVideoViewController;
    }

    @Deprecated
    @VisibleForTesting
    @NonNull
    ControllerState getState() {
        return mState;
    }
}

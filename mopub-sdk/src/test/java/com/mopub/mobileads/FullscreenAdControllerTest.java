// Copyright 2018-2021 Twitter, Inc.
// Licensed under the MoPub SDK License Agreement
// https://www.mopub.com/legal/sdk-license-agreement/

package com.mopub.mobileads;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.mopub.common.CloseableLayout;
import com.mopub.common.DataKeys;
import com.mopub.common.FullAdType;
import com.mopub.common.test.support.SdkTestRunner;
import com.mopub.network.Networking;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

import static com.mopub.mobileads.AdData.DEFAULT_DURATION_FOR_CLOSE_BUTTON_MILLIS;
import static com.mopub.mobileads.AdData.MILLIS_IN_SECOND;
import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(SdkTestRunner.class)
public class FullscreenAdControllerTest {
    private static final String EXPECTED_HTML_DATA = "htmlData";
    private static final int REWARDED_DURATION_IN_SECONDS = 25;
    private static final int SHOW_CLOSE_BUTTON_DELAY = REWARDED_DURATION_IN_SECONDS * MILLIS_IN_SECOND;
    private static final String COMPANION_RESOURCE = "resource";
    private static final int COMPANION_WIDTH = 300;
    private static final int COMPANION_HEIGHT = 250;
    private static final String COMPANION_CLICKTHROUGH_URL = "clickthrough";
    private static final int VIDEO_DURATION_MS = 29875;
    private static final String IMAGE_CLICKDESTINATION_URL = "click_destination";
    private static final String IMAGE_JSON =
            "{\"image\":\"imageurl\",\"w\":250,\"h\":200,\"clk\":\"" + IMAGE_CLICKDESTINATION_URL + "\"}";
    private static final String DSP_CREATIVE_ID = "dsp";

    private Activity activity;
    private long broadcastIdentifier;
    private AdData adData;
    private FullscreenAdController subject;
    private VastVideoConfig vastVideoConfig;
    private VastCompanionAdConfig vastCompanionAdConfig;
    private Set<VastCompanionAdConfig> vastCompanionAdConfigs;
    private List<VastTracker> companionClickTrackers;
    private List<VastTracker> companionCreativeViewTrackers;
    private BroadcastReceiver broadcastReceiver;

    @Mock
    Intent mockIntent;
    @Mock
    Bundle mockBundle;

    @Before
    public void setUp() throws Exception {
        Networking.clearForTesting();

        activity = spy(Robolectric.buildActivity(Activity.class).create().get());


        companionClickTrackers = new ArrayList<>();
        companionClickTrackers.add(new VastTracker("click1", VastTracker.MessageType.TRACKING_URL, false));
        companionClickTrackers.add(new VastTracker("click2", VastTracker.MessageType.TRACKING_URL, false));
        companionCreativeViewTrackers = new ArrayList<>();
        companionCreativeViewTrackers.add(new VastTracker("companion_view1", VastTracker.MessageType.TRACKING_URL, false));
        companionCreativeViewTrackers.add(new VastTracker("companion_view2", VastTracker.MessageType.TRACKING_URL, false));

        VastResource vastResource = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.BLURRED_LAST_FRAME,
                VastResource.CreativeType.IMAGE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastCompanionAdConfig = new VastCompanionAdConfig(
                COMPANION_WIDTH,
                COMPANION_HEIGHT,
                vastResource,
                COMPANION_CLICKTHROUGH_URL,
                companionClickTrackers,
                companionCreativeViewTrackers,
                null);
        vastVideoConfig = new VastVideoConfig();
        vastVideoConfig.setNetworkMediaFileUrl("video_url");
        vastVideoConfig.setDiskMediaFileUrl("disk_video_path");
        vastVideoConfig.addVastCompanionAdConfig(vastCompanionAdConfig);
        vastCompanionAdConfigs = new HashSet<>();
        vastCompanionAdConfigs.add(vastCompanionAdConfig);
        broadcastIdentifier = 112233;
        adData = new AdData.Builder()
                .adPayload(EXPECTED_HTML_DATA)
                .broadcastIdentifier(broadcastIdentifier)
                .rewardedDurationSeconds(REWARDED_DURATION_IN_SECONDS)
                .vastVideoConfig(vastVideoConfig.toJsonString())
                .dspCreativeId(DSP_CREATIVE_ID)
                .fullAdType(FullAdType.MRAID)
                .build();
        final Bundle bundle = new Bundle();
        bundle.putParcelable(DataKeys.AD_DATA_KEY, adData);
        when(mockIntent.getExtras()).thenReturn(bundle);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);
    }

    @After
    public void tearDown() {
        LocalBroadcastManager.getInstance(activity).unregisterReceiver(broadcastReceiver);
        Networking.clearForTesting();
    }

    @Test
    public void constructor_withRewardedTrue_shouldInitializeShowCloseButtonDelay() {
        adData.setRewarded(true);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertThat(subject.getShowCloseButtonDelayMillis()).isEqualTo(SHOW_CLOSE_BUTTON_DELAY);
    }

    @Test
    public void constructor_whenRewardedDurationIsNegative_shouldUseDefaultRewardedDuration() {
        adData.setRewarded(true);
        adData.setRewardedDurationSeconds(-3);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertThat(subject.getShowCloseButtonDelayMillis())
                .isEqualTo(DEFAULT_DURATION_FOR_CLOSE_BUTTON_MILLIS);
    }

    @Test
    public void constructor_whenRewardedDurationIsLongerThanDefault_shouldUseRewardedDuration() {
        adData.setRewarded(true);
        adData.setRewardedDurationSeconds(90);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertThat(subject.getShowCloseButtonDelayMillis()).isEqualTo(90 * 1000);
    }

    @Test
    public void constructor_shouldSetCloseableLayoutToVisible() {
        assertThat(subject.getCloseableLayout().isCloseVisible()).isTrue();
    }

    @Test
    public void constructor_withRewardedTrue_shouldSetCloseableLayoutToInvisible_shouldSetBackButtonEnabledFalse() {
        adData.setRewarded(true);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertThat(subject.getCloseableLayout().isCloseVisible()).isFalse();
        assertThat(subject.backButtonEnabled()).isFalse();
    }

    @Test
    public void constructor_withRewardedFalse_shouldSetCloseableLayoutToVisible_shouldSetBackButtonEnabledTrue() {
        adData.setRewarded(false);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertThat(subject.getCloseableLayout().isCloseVisible()).isTrue();
        assertThat(subject.backButtonEnabled()).isTrue();
    }

    @Test
    public void constructor_shouldInitializeRadialCountdownWidget_shouldInitializeCountdownRunnable() {
        adData.setRewarded(true);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        RadialCountdownWidget radialCountdownWidget = subject.getRadialCountdownWidget();
        assertThat(subject.isCalibrationDone()).isEqualTo(true);
        assertThat(radialCountdownWidget.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(radialCountdownWidget.getImageViewDrawable().getInitialCountdownMilliseconds())
                .isEqualTo(SHOW_CLOSE_BUTTON_DELAY);
        assertThat(subject.getCountdownRunnable()).isNotNull();
    }

    @Test
    public void constructor_withJsonImage_shouldSetImageView_shouldSetClickDestinationUrl() {
        adData.setFullAdType(FullAdType.JSON);
        adData.setAdPayload(IMAGE_JSON);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertNotNull(subject.getImageView());
        assertThat(subject.getCloseableLayout().getChildAt(0)).isEqualTo(subject.getImageView());
        assertThat(subject.getImageClickDestinationUrl()).isEqualTo(IMAGE_CLICKDESTINATION_URL);
        assertThat(subject.getState()).isEqualTo(FullscreenAdController.ControllerState.IMAGE);
    }

    @Test
    public void constructor_withMraid_shouldSetUpMoPubWebViewController() {
        MoPubWebViewController webViewController = subject.getMoPubWebViewController();
        assertNotNull(webViewController);
        assertThat(webViewController.mWeakActivity.get()).isEqualTo(activity);
        assertThat(webViewController.mDspCreativeId).isEqualTo(DSP_CREATIVE_ID);
        assertNotNull(webViewController.mBaseWebViewListener);
        assertNotNull(webViewController.mWebView);
        assertTrue(webViewController.mIsPaused);
        assertThat(subject.getState()).isEqualTo(FullscreenAdController.ControllerState.MRAID);
    }

    @Test
    public void constructor_withVast_shouldSetUpVastVideoViewController() {
        adData.setFullAdType(FullAdType.VAST);

        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        assertThat(subject.getVideoViewController()).isInstanceOf(VastVideoViewController.class);
        assertThat(subject.getState()).isEqualTo(FullscreenAdController.ControllerState.VIDEO);
    }

    @Test
    public void pause_shouldStopRunnables() {
        adData.setRewarded(true);
        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);
        subject.resume();

        subject.pause();

        assertThat(subject.getCountdownRunnable().isRunning()).isFalse();
    }

    @Test
    public void resume_shouldStartRunnables() {
        adData.setRewarded(true);
        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        subject.resume();

        assertThat(subject.getCountdownRunnable().isRunning()).isTrue();
    }

    @Test
    public void destroy_shouldStopRunnables() {
        adData.setRewarded(true);
        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);
        subject.resume();

        subject.destroy();

        assertThat(subject.getCountdownRunnable().isRunning()).isFalse();
    }

    @Test
    public void showCloseButton_shouldToggleVisibilityStatesAndFireEvents() {
        adData.setRewarded(true);
        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        RadialCountdownWidget radialCountdownWidget = subject.getRadialCountdownWidget();

        assertThat(subject.getCloseableLayout().isCloseVisible()).isFalse();
        assertThat(radialCountdownWidget.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(subject.isShowCloseButtonEventFired()).isFalse();
        assertThat(subject.isRewarded()).isFalse();
        subject.resume();

        subject.showCloseButton();

        assertThat(subject.getCloseableLayout().isCloseVisible()).isTrue();
        assertThat(radialCountdownWidget.getVisibility()).isEqualTo(View.GONE);
        assertThat(subject.isShowCloseButtonEventFired()).isTrue();
        assertThat(subject.isRewarded()).isTrue();
    }

    @Test
    public void useCustomCloseChangedTrue_withRewardedFalse_shouldHideCloseButton() {
        final CloseableLayout mockCloseableLayout = mock(CloseableLayout.class);
        subject.setCloseableLayout(mockCloseableLayout);

        subject.useCustomCloseChanged(true);

        verify(mockCloseableLayout).setCloseVisible(false);
    }

    @Test
    public void useCustomCloseChangedTrue_withRewardedTrue_shouldDoNothing() {
        final CloseableLayout mockCloseableLayout = mock(CloseableLayout.class);
        subject.setCloseableLayout(mockCloseableLayout);
        adData.setRewarded(true);

        subject.useCustomCloseChanged(true);

        verify(mockCloseableLayout, never()).setCloseVisible(false);
    }

    @Test
    public void useCustomCloseChangedFalse_withShowCloseButtonEventFired_shouldShowCloseButton() {
        final CloseableLayout mockCloseableLayout = mock(CloseableLayout.class);
        subject.setCloseableLayout(mockCloseableLayout);
        subject.showCloseButton();
        subject.useCustomCloseChanged(true);

        subject.useCustomCloseChanged(false);

        verify(mockCloseableLayout).setCloseVisible(false);
        verify(mockCloseableLayout, times(2)).setCloseVisible(true);
    }

    @Test
    public void onCompanionAdReady_withVastCompanionAdConfig_withBlurredLastVideoFrame_shouldSetUpCompanionAd() {
        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        final ImageView blurredLastVideoFrameImageView = subject.getImageView();
        assertNull(blurredLastVideoFrameImageView.getParent());
        assertThat(blurredLastVideoFrameImageView.getVisibility()).isEqualTo(View.VISIBLE);
        final ShadowView blurredLastVideoFrameImageViewShadow = shadowOf(blurredLastVideoFrameImageView);
        // This has been changed for the new player which allows a click on the blurred frame
        assertThat(blurredLastVideoFrameImageViewShadow.getOnClickListener()).isNotNull();
        assertNull(subject.getImageClickDestinationUrl());
        assertNotNull(subject.getBlurLastVideoFrameTask());
        final VideoCtaButtonWidget videoCtaButtonWidget = subject.getVideoCtaButtonWidget();
        assertNotNull(videoCtaButtonWidget);
        assertTrue(videoCtaButtonWidget.hasOnClickListeners());
        assertThat(videoCtaButtonWidget.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(subject.getSelectedVastCompanionAdConfig()).isEqualTo(vastCompanionAdConfig);
    }

    @Test
    public void onCompanionAdsReady_withAllTypes_shouldChooseHtmlCompanionAd() {
        final List<VastCompanionAdConfig> companionAdConfigs = new ArrayList<>();
        VastResource[] vastResources = new VastResource[4];
        vastResources[0] = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.STATIC_RESOURCE,
                VastResource.CreativeType.IMAGE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastResources[1] = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.STATIC_RESOURCE,
                VastResource.CreativeType.JAVASCRIPT,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastResources[2] = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.HTML_RESOURCE,
                VastResource.CreativeType.NONE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastResources[3] = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.IFRAME_RESOURCE,
                VastResource.CreativeType.JAVASCRIPT,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        for (final VastResource vastResource : vastResources) {
            companionAdConfigs.add(new VastCompanionAdConfig(
                    COMPANION_WIDTH,
                    COMPANION_HEIGHT,
                    vastResource,
                    COMPANION_CLICKTHROUGH_URL,
                    companionClickTrackers,
                    companionCreativeViewTrackers,
                    null));
        }

        subject.onCompanionAdsReady(new HashSet<>(companionAdConfigs), VIDEO_DURATION_MS);

        assertThat(subject.getSelectedVastCompanionAdConfig()).isEqualTo(companionAdConfigs.get(2));
        assertNotNull(subject.getMoPubWebViewController());
        assertNotNull(subject.getMoPubWebViewController().mWebView);
        assertNull(subject.getImageView());
    }


    @Test
    public void onCompanionAdsReady_withImage_shouldChooseImageCompanionAd() {
        VastResource vastResource = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.STATIC_RESOURCE,
                VastResource.CreativeType.IMAGE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastCompanionAdConfig = new VastCompanionAdConfig(
                COMPANION_WIDTH,
                COMPANION_HEIGHT,
                vastResource,
                COMPANION_CLICKTHROUGH_URL,
                companionClickTrackers,
                companionCreativeViewTrackers,
                null);
        vastCompanionAdConfigs.add(vastCompanionAdConfig);

        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        assertThat(subject.getSelectedVastCompanionAdConfig()).isEqualTo(vastCompanionAdConfig);
        assertNotNull(subject.getImageView());
        assertThat(subject.getImageView().getVisibility()).isEqualTo(View.VISIBLE);
        assertTrue(subject.getImageView().hasOnClickListeners());
        assertNull(subject.getVideoCtaButtonWidget());
    }

    @Test
    public void onAdClicked_withNoCompanionAd_withMraid_shouldBroadcastClick() throws InterruptedException {
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void onAdClicked_withNoCompanionAd_withImage_shouldBroadcastClick() throws InterruptedException {
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());
        adData.setFullAdType(FullAdType.JSON);
        adData.setAdPayload(IMAGE_JSON);
        subject = new FullscreenAdController(activity, mockBundle, mockIntent, adData);

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void onAdClicked_withBlurredLastFrameCompanion_shouldBroadcastClick() throws InterruptedException {
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());
        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void onAdClicked_withStaticImageCompanion_shouldBroadcastClick() throws InterruptedException {
        VastResource vastResource = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.STATIC_RESOURCE,
                VastResource.CreativeType.IMAGE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastCompanionAdConfig = new VastCompanionAdConfig(
                COMPANION_WIDTH,
                COMPANION_HEIGHT,
                vastResource,
                COMPANION_CLICKTHROUGH_URL,
                companionClickTrackers,
                companionCreativeViewTrackers,
                null);
        vastCompanionAdConfigs.clear();
        vastCompanionAdConfigs.add(vastCompanionAdConfig);
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());
        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void onAdClicked_withJavascriptCompanion_shouldBroadcastClick() throws InterruptedException {
        VastResource vastResource = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.STATIC_RESOURCE,
                VastResource.CreativeType.JAVASCRIPT,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastCompanionAdConfig = new VastCompanionAdConfig(
                COMPANION_WIDTH,
                COMPANION_HEIGHT,
                vastResource,
                COMPANION_CLICKTHROUGH_URL,
                companionClickTrackers,
                companionCreativeViewTrackers,
                null);
        vastCompanionAdConfigs.clear();
        vastCompanionAdConfigs.add(vastCompanionAdConfig);
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());
        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void onAdClicked_withHtmlCompanion_shouldBroadcastClick() throws InterruptedException {
        VastResource vastResource = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.HTML_RESOURCE,
                VastResource.CreativeType.NONE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastCompanionAdConfig = new VastCompanionAdConfig(
                COMPANION_WIDTH,
                COMPANION_HEIGHT,
                vastResource,
                COMPANION_CLICKTHROUGH_URL,
                companionClickTrackers,
                companionCreativeViewTrackers,
                null);
        vastCompanionAdConfigs.clear();
        vastCompanionAdConfigs.add(vastCompanionAdConfig);
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());
        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void onAdClicked_withIframeCompanion_shouldBroadcastClick() throws InterruptedException {
        VastResource vastResource = new VastResource(
                COMPANION_RESOURCE,
                VastResource.Type.IFRAME_RESOURCE,
                VastResource.CreativeType.NONE,
                COMPANION_WIDTH,
                COMPANION_HEIGHT);
        vastCompanionAdConfig = new VastCompanionAdConfig(
                COMPANION_WIDTH,
                COMPANION_HEIGHT,
                vastResource,
                COMPANION_CLICKTHROUGH_URL,
                companionClickTrackers,
                companionCreativeViewTrackers,
                null);
        vastCompanionAdConfigs.clear();
        vastCompanionAdConfigs.add(vastCompanionAdConfig);
        final Semaphore semaphore = new Semaphore(0);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertThat(intent.getAction()).isEqualTo("com.mopub.action.fullscreen.click");
                assertThat(intent.getLongExtra(DataKeys.BROADCAST_IDENTIFIER_KEY, -1)).isEqualTo(broadcastIdentifier);

                semaphore.release();
            }
        };
        LocalBroadcastManager.getInstance(activity).registerReceiver(broadcastReceiver,
                new EventForwardingBroadcastReceiver(null, broadcastIdentifier).getIntentFilter());
        subject.onCompanionAdsReady(vastCompanionAdConfigs, VIDEO_DURATION_MS);

        subject.onAdClicked(activity, adData);

        semaphore.acquire();
    }

    @Test
    public void destroy_withBlurLastVideoFrameTaskStillPending_shouldCancelTask() {

        VastVideoBlurLastVideoFrameTask mockBlurLastVideoFrameTask = mock(VastVideoBlurLastVideoFrameTask.class);
        when(mockBlurLastVideoFrameTask.getStatus()).thenReturn(AsyncTask.Status.PENDING);
        subject.setBlurLastVideoFrameTask(mockBlurLastVideoFrameTask);

        subject.destroy();

        verify(mockBlurLastVideoFrameTask).cancel(true);
    }

    @Test
    public void destroy_withBlurLastVideoFrameTaskFinished_shouldCancelTask() {

        VastVideoBlurLastVideoFrameTask mockBlurLastVideoFrameTask = mock(VastVideoBlurLastVideoFrameTask.class);
        when(mockBlurLastVideoFrameTask.getStatus()).thenReturn(AsyncTask.Status.FINISHED);
        subject.setBlurLastVideoFrameTask(mockBlurLastVideoFrameTask);

        subject.destroy();

        verify(mockBlurLastVideoFrameTask).cancel(anyBoolean());
    }
}

package com.mopub.tests;

import android.os.SystemClock;
import androidx.test.espresso.ViewInteraction;
import androidx.test.filters.LargeTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mopub.framework.models.AdLabels;
import com.mopub.framework.pages.AdDetailPage;
import com.mopub.simpleadsdemo.R;
import com.mopub.tests.base.MoPubBaseTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class RewardedVideoAdTest extends MoPubBaseTestCase {

    // Test Variables
    private static final String TITLE = AdLabels.REWARDED_VIDEO;
    private static final String WEB_PAGE_LINK = "https://www.mopub.com/?q=companionClickThrough640x360";
    private static final int VIDEO_AD_WAIT_TIME_IN_MILLIS = 35000;

    /*
     * Verify that the Rewarded Video Ad loads & shows on the app.
     */
    @Test
    public void adsDetailsPage_withClickOnLoadAdButtonAndThenShowAdButton_shouldLoadMoPubRewardedVideo() {
        onData(hasToString(startsWith(TITLE)))
                .inAdapterView(withId(android.R.id.list))
                .perform(click());

        final AdDetailPage adDetailPage = new AdDetailPage();

        onView(withId(R.id.load_button)).perform(click());

        ViewInteraction showButtonElement = onView(withId(R.id.show_button)); //show ad on click
        adDetailPage.clickElement(showButtonElement);

        SystemClock.sleep(VIDEO_AD_WAIT_TIME_IN_MILLIS); // wait for video ad time to get to zero

        final ViewInteraction element = onView(withId(android.R.id.content));

        assertTrue(adDetailPage.waitForElement(element));
    }

    /*
     * Verify that the user is correctly navigated to MoPub browser.
     */
    @Test
    public void adsDetailsPage_withClickOnAd_shouldLoadMoPubBrowser() {
        onData(hasToString(startsWith(TITLE)))
                .inAdapterView(withId(android.R.id.list))
                .perform(click());

        final AdDetailPage adDetailPage = new AdDetailPage();

        onView(withId(R.id.load_button)).perform(click());

        ViewInteraction showButtonElement = onView(withId(R.id.show_button)); //show ad on click
        adDetailPage.clickElement(showButtonElement);

        SystemClock.sleep(VIDEO_AD_WAIT_TIME_IN_MILLIS); // wait for video ad time to get to zero

        onView(withId(android.R.id.content)).perform(click());

        final ViewInteraction browserLinkElement = onView(withText(WEB_PAGE_LINK));

        assertTrue(adDetailPage.waitForElement(browserLinkElement));
    }
}

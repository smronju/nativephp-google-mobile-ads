package com.nativephp.plugins.googlemobileads

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject

private const val TAG = "GoogleMobileAds"
private val mainHandler = Handler(Looper.getMainLooper())

// ── Ad instance holder ────────────────────────────────────────────────────────

private object AdHolder {
    var bannerView: AdView? = null
    var interstitial: InterstitialAd? = null
    var rewarded: RewardedAd? = null
    var rewardedInterstitial: RewardedInterstitialAd? = null
    var appOpen: AppOpenAd? = null
}

// ── Event dispatch helper ─────────────────────────────────────────────────────

/**
 * Was originally hand-rolled WebView JS injection requiring `WebViewProvider`,
 * which (a) force-created a hidden WebView via the non-null `getWebView()` on
 * apps with no WebView anywhere, and (b) never reached
 * `NativeElementBridge.sendNativeEvent()`, so `#[On(...)]` listeners on a
 * native-UI-only screen never fired no matter what. `NativeActionCoordinator`
 * is the core's own dispatcher — same one Camera/Microphone use — and already
 * handles both the WebView-optional case and the native element event queue.
 */
private fun FragmentActivity.dispatchAdEvent(eventClass: String, payload: Map<String, Any>) {
    val payloadJson = JSONObject(payload).toString()
    NativeActionCoordinator.dispatchEvent(this, eventClass, payloadJson)
}

// ── Full-screen callback factory ──────────────────────────────────────────────

private fun fullScreenCallback(activity: FragmentActivity, adType: String): FullScreenContentCallback =
    object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() {
            activity.dispatchAdEvent(
                "NativePHP\\GoogleMobileAds\\Events\\AdOpened",
                mapOf("adType" to adType)
            )
        }

        override fun onAdDismissedFullScreenContent() {
            activity.dispatchAdEvent(
                "NativePHP\\GoogleMobileAds\\Events\\AdClosed",
                mapOf("adType" to adType)
            )
        }

        override fun onAdFailedToShowFullScreenContent(error: AdError) {
            activity.dispatchAdEvent(
                "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                mapOf("adType" to adType, "adUnitId" to "", "errorCode" to error.code, "errorMessage" to error.message)
            )
        }

        override fun onAdImpression() {
            activity.dispatchAdEvent(
                "NativePHP\\GoogleMobileAds\\Events\\AdImpression",
                mapOf("adType" to adType)
            )
        }

        override fun onAdClicked() {
            activity.dispatchAdEvent(
                "NativePHP\\GoogleMobileAds\\Events\\AdClicked",
                mapOf("adType" to adType)
            )
        }
    }

// ── Bridge Functions ──────────────────────────────────────────────────────────

object GoogleMobileAdsFunctions {

    // ── Initialize ────────────────────────────────────────────────────────────

    class Initialize(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mainHandler.post {
                MobileAds.initialize(activity) {
                    Log.i(TAG, "MobileAds SDK initialized")
                }
            }
            return BridgeResponse.success(mapOf("status" to "initialized"))
        }
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    class ShowBanner(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val adUnitId = parameters["ad_unit_id"] as? String
                ?: return BridgeResponse.error("MISSING_PARAM", "ad_unit_id is required")

            val position = parameters["position"] as? String ?: "bottom"
            val sizeKey  = parameters["size"] as? String ?: "adaptive"

            mainHandler.post {
                val adView = AdView(activity)
                adView.adUnitId = adUnitId
                adView.setAdSize(resolveAdSize(sizeKey))

                adView.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        activity.dispatchAdEvent(
                            "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                            mapOf("adType" to "banner", "adUnitId" to adUnitId)
                        )
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        activity.dispatchAdEvent(
                            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                            mapOf("adType" to "banner", "adUnitId" to adUnitId, "errorCode" to error.code, "errorMessage" to error.message)
                        )
                    }
                    override fun onAdOpened()     { activity.dispatchAdEvent("NativePHP\\GoogleMobileAds\\Events\\AdOpened",    mapOf("adType" to "banner")) }
                    override fun onAdClosed()     { activity.dispatchAdEvent("NativePHP\\GoogleMobileAds\\Events\\AdClosed",    mapOf("adType" to "banner")) }
                    override fun onAdImpression() { activity.dispatchAdEvent("NativePHP\\GoogleMobileAds\\Events\\AdImpression",mapOf("adType" to "banner")) }
                    override fun onAdClicked()    { activity.dispatchAdEvent("NativePHP\\GoogleMobileAds\\Events\\AdClicked",   mapOf("adType" to "banner")) }
                }

                AdHolder.bannerView?.let { old ->
                    (activity.window.decorView.rootView as? ViewGroup)?.removeView(old)
                    old.destroy()
                }

                val gravity = if (position == "top") Gravity.TOP else Gravity.BOTTOM
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    gravity
                )

                // Added straight to the decor view with no inset awareness, this
                // rendered flush against the physical edge — on an edge-to-edge
                // window that's on top of the status bar (position "top") or
                // behind the gesture/nav bar (position "bottom"), covering system
                // UI outright. AdMob's own placement policy prohibits ads
                // overlapping system chrome, so the relevant system-bar inset is
                // applied as a margin instead of a bare 0.
                val systemBarInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.systemBars())
                if (position == "top") {
                    params.topMargin = systemBarInsets?.top ?: 0
                } else {
                    // The system-bar inset alone only clears the gesture/nav bar,
                    // not the host app's own bottom tab bar sitting above it —
                    // this Kotlin code has no visibility into that Compose/SwiftUI
                    // layout to measure it exactly. Material 3's NavigationBar
                    // spec (80dp) is used as a reasonable estimate; a host with a
                    // taller or shorter bottom bar will need to adjust this.
                    val estimatedBottomBarHeight = (80 * activity.resources.displayMetrics.density).toInt()
                    params.bottomMargin = (systemBarInsets?.bottom ?: 0) + estimatedBottomBarHeight
                }

                (activity.window.decorView.rootView as? ViewGroup)?.addView(adView, params)
                AdHolder.bannerView = adView

                adView.loadAd(AdRequest.Builder().build())
            }

            return BridgeResponse.success(mapOf("status" to "loading"))
        }

        private fun resolveAdSize(size: String): AdSize {
            val metrics = activity.resources.displayMetrics
            val adWidth = (metrics.widthPixels / metrics.density).toInt()
            return when (size) {
                "banner"           -> AdSize.BANNER
                "large_banner"     -> AdSize.LARGE_BANNER
                "medium_rectangle" -> AdSize.MEDIUM_RECTANGLE
                else               -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
            }
        }
    }

    class HideBanner(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mainHandler.post {
                AdHolder.bannerView?.let { banner ->
                    (activity.window.decorView.rootView as? ViewGroup)?.removeView(banner)
                    banner.destroy()
                    AdHolder.bannerView = null
                }
            }
            return BridgeResponse.success(mapOf("status" to "hidden"))
        }
    }

    // ── Interstitial ──────────────────────────────────────────────────────────

    class LoadInterstitial(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val adUnitId = parameters["ad_unit_id"] as? String
                ?: return BridgeResponse.error("MISSING_PARAM", "ad_unit_id is required")

            mainHandler.post {
                InterstitialAd.load(activity, adUnitId, AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            AdHolder.interstitial = ad
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                                mapOf("adType" to "interstitial", "adUnitId" to adUnitId)
                            )
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                                mapOf("adType" to "interstitial", "adUnitId" to adUnitId, "errorCode" to error.code, "errorMessage" to error.message)
                            )
                        }
                    }
                )
            }
            return BridgeResponse.success(mapOf("status" to "loading"))
        }
    }

    class ShowInterstitial(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val ad = AdHolder.interstitial
                ?: return BridgeResponse.error("NOT_LOADED", "No interstitial loaded. Call LoadInterstitial first.")

            mainHandler.post {
                AdHolder.interstitial = null
                ad.fullScreenContentCallback = fullScreenCallback(activity, "interstitial")
                ad.show(activity)
            }
            return BridgeResponse.success(mapOf("status" to "showing"))
        }
    }

    // ── Rewarded ──────────────────────────────────────────────────────────────

    class LoadRewarded(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val adUnitId = parameters["ad_unit_id"] as? String
                ?: return BridgeResponse.error("MISSING_PARAM", "ad_unit_id is required")

            mainHandler.post {
                RewardedAd.load(activity, adUnitId, AdRequest.Builder().build(),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            AdHolder.rewarded = ad
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                                mapOf("adType" to "rewarded", "adUnitId" to adUnitId)
                            )
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                                mapOf("adType" to "rewarded", "adUnitId" to adUnitId, "errorCode" to error.code, "errorMessage" to error.message)
                            )
                        }
                    }
                )
            }
            return BridgeResponse.success(mapOf("status" to "loading"))
        }
    }

    class ShowRewarded(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val ad = AdHolder.rewarded
                ?: return BridgeResponse.error("NOT_LOADED", "No rewarded ad loaded. Call LoadRewarded first.")

            mainHandler.post {
                AdHolder.rewarded = null
                ad.fullScreenContentCallback = fullScreenCallback(activity, "rewarded")
                ad.show(activity, OnUserEarnedRewardListener { reward ->
                    activity.dispatchAdEvent(
                        "NativePHP\\GoogleMobileAds\\Events\\RewardEarned",
                        mapOf("rewardType" to reward.type, "rewardAmount" to reward.amount)
                    )
                })
            }
            return BridgeResponse.success(mapOf("status" to "showing"))
        }
    }

    // ── Rewarded Interstitial ─────────────────────────────────────────────────

    class LoadRewardedInterstitial(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val adUnitId = parameters["ad_unit_id"] as? String
                ?: return BridgeResponse.error("MISSING_PARAM", "ad_unit_id is required")

            mainHandler.post {
                RewardedInterstitialAd.load(activity, adUnitId, AdRequest.Builder().build(),
                    object : RewardedInterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedInterstitialAd) {
                            AdHolder.rewardedInterstitial = ad
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                                mapOf("adType" to "rewarded_interstitial", "adUnitId" to adUnitId)
                            )
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                                mapOf("adType" to "rewarded_interstitial", "adUnitId" to adUnitId, "errorCode" to error.code, "errorMessage" to error.message)
                            )
                        }
                    }
                )
            }
            return BridgeResponse.success(mapOf("status" to "loading"))
        }
    }

    class ShowRewardedInterstitial(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val ad = AdHolder.rewardedInterstitial
                ?: return BridgeResponse.error("NOT_LOADED", "No rewarded interstitial loaded. Call LoadRewardedInterstitial first.")

            mainHandler.post {
                AdHolder.rewardedInterstitial = null
                ad.fullScreenContentCallback = fullScreenCallback(activity, "rewarded_interstitial")
                ad.show(activity, OnUserEarnedRewardListener { reward ->
                    activity.dispatchAdEvent(
                        "NativePHP\\GoogleMobileAds\\Events\\RewardEarned",
                        mapOf("rewardType" to reward.type, "rewardAmount" to reward.amount)
                    )
                })
            }
            return BridgeResponse.success(mapOf("status" to "showing"))
        }
    }

    // ── App Open ──────────────────────────────────────────────────────────────

    class LoadAppOpen(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val adUnitId = parameters["ad_unit_id"] as? String
                ?: return BridgeResponse.error("MISSING_PARAM", "ad_unit_id is required")

            mainHandler.post {
                AppOpenAd.load(activity, adUnitId, AdRequest.Builder().build(),
                    object : AppOpenAd.AppOpenAdLoadCallback() {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            AdHolder.appOpen = ad
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                                mapOf("adType" to "app_open", "adUnitId" to adUnitId)
                            )
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            activity.dispatchAdEvent(
                                "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                                mapOf("adType" to "app_open", "adUnitId" to adUnitId, "errorCode" to error.code, "errorMessage" to error.message)
                            )
                        }
                    }
                )
            }
            return BridgeResponse.success(mapOf("status" to "loading"))
        }
    }

    class ShowAppOpen(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val ad = AdHolder.appOpen
                ?: return BridgeResponse.error("NOT_LOADED", "No app open ad loaded. Call LoadAppOpen first.")

            mainHandler.post {
                AdHolder.appOpen = null
                ad.fullScreenContentCallback = fullScreenCallback(activity, "app_open")
                ad.show(activity)
            }
            return BridgeResponse.success(mapOf("status" to "showing"))
        }
    }
}

import Foundation
import GoogleMobileAds
import UIKit

@objc public class GoogleMobileAdsFunctions: NSObject {

    // MARK: - Initialize

    @objc public class Initialize: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let appId = parameters["app_id"] as? String, !appId.isEmpty else {
                return BridgeResponse.error(code: "MISSING_PARAM", message: "app_id is required")
            }

            DispatchQueue.main.async {
                MobileAds.shared.start { _ in }
            }

            return BridgeResponse.success(data: ["status": "initialized"])
        }
    }

    // MARK: - Banner

    @objc public class ShowBanner: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let adUnitId = parameters["ad_unit_id"] as? String else {
                return BridgeResponse.error(code: "MISSING_PARAM", message: "ad_unit_id is required")
            }

            let position = parameters["position"] as? String ?? "bottom"
            let size = parameters["size"] as? String ?? "adaptive"

            DispatchQueue.main.async {
                guard let rootVC = UIApplication.shared.keyWindow?.rootViewController else { return }

                AdViewHolder.shared.bannerView?.removeFromSuperview()

                let bannerView = BannerView()
                bannerView.adUnitID = adUnitId
                bannerView.rootViewController = rootVC
                bannerView.adSize = Self.resolveAdSize(size)

                let bannerDelegate = BannerEventDelegate(adUnitId: adUnitId)
                bannerView.delegate = bannerDelegate
                AdViewHolder.shared.bannerDelegate = bannerDelegate

                let request = Request()
                bannerView.load(request)

                let window = UIApplication.shared.keyWindow
                window?.addSubview(bannerView)

                bannerView.translatesAutoresizingMaskIntoConstraints = false
                // The window's safe area stops at the status bar and home indicator;
                // the host app's own navigation bar and tab bar sit inside it, so the
                // banner is pushed past those too rather than drawn over them.
                let verticalAnchor = position == "top"
                    ? bannerView.topAnchor.constraint(
                        equalTo: window!.safeAreaLayoutGuide.topAnchor,
                        constant: Self.hostBarInset(in: window!, top: true)
                    )
                    : bannerView.bottomAnchor.constraint(
                        equalTo: window!.safeAreaLayoutGuide.bottomAnchor,
                        constant: -Self.hostBarInset(in: window!, top: false)
                    )

                NSLayoutConstraint.activate([
                    bannerView.centerXAnchor.constraint(equalTo: window!.centerXAnchor),
                    verticalAnchor,
                ])

                AdViewHolder.shared.bannerView = bannerView
            }

            return BridgeResponse.success(data: ["status": "loading"])
        }

        /// How far the host app's own bar reaches past the window's safe area:
        /// the navigation bar below the status bar for `top`, the tab bar above
        /// the home indicator for `bottom`. Measured from the visible bar when
        /// there is one, otherwise the standard UIKit heights (44pt / 49pt), the
        /// same kind of estimate the Android side uses for its top bar.
        private static func hostBarInset(in window: UIWindow, top: Bool) -> CGFloat {
            if top {
                guard let bar: UINavigationBar = visibleBar(in: window) else { return 44 }
                let frame = bar.convert(bar.bounds, to: window)

                return max(0, frame.maxY - window.safeAreaInsets.top)
            }

            guard let bar: UITabBar = visibleBar(in: window) else { return 49 }
            let frame = bar.convert(bar.bounds, to: window)

            return max(0, window.bounds.height - window.safeAreaInsets.bottom - frame.minY)
        }

        private static func visibleBar<Bar: UIView>(in view: UIView) -> Bar? {
            for subview in view.subviews where !subview.isHidden && subview.alpha > 0 {
                if let bar = subview as? Bar, bar.bounds.height > 0 {
                    return bar
                }

                if let bar: Bar = visibleBar(in: subview) {
                    return bar
                }
            }

            return nil
        }

        private static func resolveAdSize(_ size: String) -> AdSize {
            switch size {
            case "banner":            return AdSizeBanner
            case "large_banner":      return AdSizeLargeBanner
            case "medium_rectangle":  return AdSizeMediumRectangle
            default:
                let width = UIScreen.main.bounds.width
                return currentOrientationAnchoredAdaptiveBanner(width: width)
            }
        }
    }

    @objc public class HideBanner: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            DispatchQueue.main.async {
                AdViewHolder.shared.bannerView?.removeFromSuperview()
                AdViewHolder.shared.bannerView = nil
            }
            return BridgeResponse.success(data: ["status": "hidden"])
        }
    }

    // MARK: - Interstitial

    @objc public class LoadInterstitial: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let adUnitId = parameters["ad_unit_id"] as? String else {
                return BridgeResponse.error(code: "MISSING_PARAM", message: "ad_unit_id is required")
            }

            DispatchQueue.main.async {
                InterstitialAd.load(
                    with: adUnitId,
                    request: Request()
                ) { ad, error in
                    if let error = error {
                        LaravelBridge.shared.send?(
                            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                            [
                                "adType": "interstitial",
                                "adUnitId": adUnitId,
                                "errorCode": (error as NSError).code,
                                "errorMessage": error.localizedDescription,
                            ]
                        )
                        return
                    }

                    AdViewHolder.shared.interstitial = ad
                    LaravelBridge.shared.send?(
                        "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                        ["adType": "interstitial", "adUnitId": adUnitId]
                    )
                }
            }

            return BridgeResponse.success(data: ["status": "loading"])
        }
    }

    @objc public class ShowInterstitial: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let ad = AdViewHolder.shared.interstitial else {
                return BridgeResponse.error(code: "NOT_LOADED", message: "No interstitial ad loaded. Call LoadInterstitial first.")
            }

            DispatchQueue.main.async {
                guard let rootVC = UIApplication.shared.keyWindow?.rootViewController else { return }
                let delegate = FullScreenDelegate(adType: "interstitial")
                ad.fullScreenContentDelegate = delegate
                AdViewHolder.shared.interstitialDelegate = delegate
                AdViewHolder.shared.interstitial = nil
                ad.present(from: rootVC)
            }

            return BridgeResponse.success(data: ["status": "showing"])
        }
    }

    // MARK: - Rewarded

    @objc public class LoadRewarded: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let adUnitId = parameters["ad_unit_id"] as? String else {
                return BridgeResponse.error(code: "MISSING_PARAM", message: "ad_unit_id is required")
            }

            DispatchQueue.main.async {
                RewardedAd.load(
                    with: adUnitId,
                    request: Request()
                ) { ad, error in
                    if let error = error {
                        LaravelBridge.shared.send?(
                            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                            [
                                "adType": "rewarded",
                                "adUnitId": adUnitId,
                                "errorCode": (error as NSError).code,
                                "errorMessage": error.localizedDescription,
                            ]
                        )
                        return
                    }

                    AdViewHolder.shared.rewarded = ad
                    LaravelBridge.shared.send?(
                        "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                        ["adType": "rewarded", "adUnitId": adUnitId]
                    )
                }
            }

            return BridgeResponse.success(data: ["status": "loading"])
        }
    }

    @objc public class ShowRewarded: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let ad = AdViewHolder.shared.rewarded else {
                return BridgeResponse.error(code: "NOT_LOADED", message: "No rewarded ad loaded. Call LoadRewarded first.")
            }

            DispatchQueue.main.async {
                guard let rootVC = UIApplication.shared.keyWindow?.rootViewController else { return }
                let delegate = FullScreenDelegate(adType: "rewarded")
                ad.fullScreenContentDelegate = delegate
                AdViewHolder.shared.rewardedDelegate = delegate
                AdViewHolder.shared.rewarded = nil

                ad.present(from: rootVC) {
                    let reward = ad.adReward
                    LaravelBridge.shared.send?(
                        "NativePHP\\GoogleMobileAds\\Events\\RewardEarned",
                        [
                            "rewardType": reward.type,
                            "rewardAmount": reward.amount.intValue,
                        ]
                    )
                }
            }

            return BridgeResponse.success(data: ["status": "showing"])
        }
    }

    // MARK: - Rewarded Interstitial

    @objc public class LoadRewardedInterstitial: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let adUnitId = parameters["ad_unit_id"] as? String else {
                return BridgeResponse.error(code: "MISSING_PARAM", message: "ad_unit_id is required")
            }

            DispatchQueue.main.async {
                RewardedInterstitialAd.load(
                    with: adUnitId,
                    request: Request()
                ) { ad, error in
                    if let error = error {
                        LaravelBridge.shared.send?(
                            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                            [
                                "adType": "rewarded_interstitial",
                                "adUnitId": adUnitId,
                                "errorCode": (error as NSError).code,
                                "errorMessage": error.localizedDescription,
                            ]
                        )
                        return
                    }

                    AdViewHolder.shared.rewardedInterstitial = ad
                    LaravelBridge.shared.send?(
                        "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                        ["adType": "rewarded_interstitial", "adUnitId": adUnitId]
                    )
                }
            }

            return BridgeResponse.success(data: ["status": "loading"])
        }
    }

    @objc public class ShowRewardedInterstitial: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let ad = AdViewHolder.shared.rewardedInterstitial else {
                return BridgeResponse.error(code: "NOT_LOADED", message: "No rewarded interstitial ad loaded. Call LoadRewardedInterstitial first.")
            }

            DispatchQueue.main.async {
                guard let rootVC = UIApplication.shared.keyWindow?.rootViewController else { return }
                let delegate = FullScreenDelegate(adType: "rewarded_interstitial")
                ad.fullScreenContentDelegate = delegate
                AdViewHolder.shared.rewardedInterstitialDelegate = delegate
                AdViewHolder.shared.rewardedInterstitial = nil

                ad.present(from: rootVC, userDidEarnRewardHandler: {
                    let reward = ad.adReward
                    LaravelBridge.shared.send?(
                        "NativePHP\\GoogleMobileAds\\Events\\RewardEarned",
                        [
                            "rewardType": reward.type,
                            "rewardAmount": reward.amount.intValue,
                        ]
                    )
                })
            }

            return BridgeResponse.success(data: ["status": "showing"])
        }
    }

    // MARK: - App Open

    @objc public class LoadAppOpen: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let adUnitId = parameters["ad_unit_id"] as? String else {
                return BridgeResponse.error(code: "MISSING_PARAM", message: "ad_unit_id is required")
            }

            DispatchQueue.main.async {
                AppOpenAd.load(
                    with: adUnitId,
                    request: Request()
                ) { ad, error in
                    if let error = error {
                        LaravelBridge.shared.send?(
                            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
                            [
                                "adType": "app_open",
                                "adUnitId": adUnitId,
                                "errorCode": (error as NSError).code,
                                "errorMessage": error.localizedDescription,
                            ]
                        )
                        return
                    }

                    AdViewHolder.shared.appOpen = ad
                    LaravelBridge.shared.send?(
                        "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
                        ["adType": "app_open", "adUnitId": adUnitId]
                    )
                }
            }

            return BridgeResponse.success(data: ["status": "loading"])
        }
    }

    @objc public class ShowAppOpen: NSObject, BridgeFunction {
        public func execute(parameters: [String: Any]) -> [String: Any] {
            guard let ad = AdViewHolder.shared.appOpen else {
                return BridgeResponse.error(code: "NOT_LOADED", message: "No app open ad loaded. Call LoadAppOpen first.")
            }

            DispatchQueue.main.async {
                guard let rootVC = UIApplication.shared.keyWindow?.rootViewController else { return }
                let delegate = FullScreenDelegate(adType: "app_open")
                ad.fullScreenContentDelegate = delegate
                AdViewHolder.shared.appOpenDelegate = delegate
                AdViewHolder.shared.appOpen = nil
                ad.present(from: rootVC)
            }

            return BridgeResponse.success(data: ["status": "showing"])
        }
    }
}

// MARK: - Helpers

private class BannerEventDelegate: NSObject, BannerViewDelegate {
    private let adUnitId: String

    init(adUnitId: String) {
        self.adUnitId = adUnitId
    }

    func bannerViewDidReceiveAd(_ bannerView: BannerView) {
        // adSize.size.height is the points height AdMob actually allocated for
        // this banner — the host app needs this to reserve exactly enough
        // space for its own layout instead of guessing a static value.
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdLoaded",
            ["adType": "banner", "adUnitId": adUnitId, "heightDp": Int(bannerView.adSize.size.height)]
        )
    }

    func bannerView(_ bannerView: BannerView, didFailToReceiveAdWithError error: Error) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
            [
                "adType": "banner",
                "adUnitId": adUnitId,
                "errorCode": (error as NSError).code,
                "errorMessage": error.localizedDescription,
            ]
        )
    }

    func bannerViewWillPresentScreen(_ bannerView: BannerView) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdOpened",
            ["adType": "banner"]
        )
    }

    func bannerViewDidDismissScreen(_ bannerView: BannerView) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdClosed",
            ["adType": "banner"]
        )
    }

    func bannerViewDidRecordImpression(_ bannerView: BannerView) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdImpression",
            ["adType": "banner"]
        )
    }

    func bannerViewDidRecordClick(_ bannerView: BannerView) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdClicked",
            ["adType": "banner"]
        )
    }
}

private class FullScreenDelegate: NSObject, FullScreenContentDelegate {
    private let adType: String

    init(adType: String) {
        self.adType = adType
    }

    func adWillPresentFullScreenContent(_ ad: FullScreenPresentingAd) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdOpened",
            ["adType": adType]
        )
    }

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdClosed",
            ["adType": adType]
        )
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdFailedToLoad",
            [
                "adType": adType,
                "adUnitId": "",
                "errorCode": (error as NSError).code,
                "errorMessage": error.localizedDescription,
            ]
        )
    }

    func adDidRecordImpression(_ ad: FullScreenPresentingAd) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdImpression",
            ["adType": adType]
        )
    }

    func adDidRecordClick(_ ad: FullScreenPresentingAd) {
        LaravelBridge.shared.send?(
            "NativePHP\\GoogleMobileAds\\Events\\AdClicked",
            ["adType": adType]
        )
    }
}

// MARK: - Ad Holder

private class AdViewHolder {
    static let shared = AdViewHolder()

    var bannerView: BannerView?
    var bannerDelegate: BannerEventDelegate?
    var interstitial: InterstitialAd?
    var interstitialDelegate: FullScreenDelegate?
    var rewarded: RewardedAd?
    var rewardedDelegate: FullScreenDelegate?
    var rewardedInterstitial: RewardedInterstitialAd?
    var rewardedInterstitialDelegate: FullScreenDelegate?
    var appOpen: AppOpenAd?
    var appOpenDelegate: FullScreenDelegate?
}

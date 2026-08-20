<?php

namespace NativePHP\GoogleMobileAds;

use InvalidArgumentException;

class GoogleMobileAds
{
    /**
     * Resolve a slot name or raw ad unit ID to the correct platform ID.
     *
     * In test mode, Google's official demo IDs are returned automatically.
     * Pass a slot name defined in config('google-mobile-ads.slots') or a raw
     * ca-app-pub-... string to bypass slot resolution entirely.
     */
    public function resolveAdUnitId(string $slot): string
    {
        if (config('google-mobile-ads.test_mode')) {
            return $this->testSlotId($slot);
        }

        // Raw ad unit ID passed directly — use as-is
        if (str_starts_with($slot, 'ca-app-pub-')) {
            return $slot;
        }

        $slots = config('google-mobile-ads.slots', []);

        if (! array_key_exists($slot, $slots)) {
            throw new InvalidArgumentException("Google Mobile Ads: unknown slot \"{$slot}\". Define it in config/google-mobile-ads.php.");
        }

        $value = $slots[$slot];

        if (is_array($value)) {
            $platform = $this->platform();
            return $value[$platform] ?? throw new InvalidArgumentException("Slot \"{$slot}\" has no ID defined for platform \"{$platform}\".");
        }

        return (string) $value;
    }

    public function initialize(): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        // iOS's Initialize bridge function rejects an empty app_id outright;
        // Android's ignores the parameter entirely — sending it is harmless there.
        $appId = $this->platform() === 'ios'
            ? config('google-mobile-ads.ios_app_id')
            : config('google-mobile-ads.android_app_id');

        $this->bridgeCall('GoogleMobileAds.Initialize', [
            'app_id' => (string) $appId,
        ]);

        return $this;
    }

    public function showBanner(string $slot = 'banner', string $position = 'bottom', string $size = 'adaptive'): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.ShowBanner', [
            'ad_unit_id' => $this->resolveAdUnitId($slot),
            'position'   => $position,
            'size'       => $size,
        ]);

        return $this;
    }

    public function hideBanner(): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.HideBanner', []);

        return $this;
    }

    public function loadInterstitial(string $slot = 'interstitial'): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.LoadInterstitial', [
            'ad_unit_id' => $this->resolveAdUnitId($slot),
        ]);

        return $this;
    }

    public function showInterstitial(): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.ShowInterstitial', []);

        return $this;
    }

    public function loadRewarded(string $slot = 'rewarded'): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.LoadRewarded', [
            'ad_unit_id' => $this->resolveAdUnitId($slot),
        ]);

        return $this;
    }

    public function showRewarded(): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.ShowRewarded', []);

        return $this;
    }

    public function loadRewardedInterstitial(string $slot = 'rewarded_interstitial'): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.LoadRewardedInterstitial', [
            'ad_unit_id' => $this->resolveAdUnitId($slot),
        ]);

        return $this;
    }

    public function showRewardedInterstitial(): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.ShowRewardedInterstitial', []);

        return $this;
    }

    public function loadAppOpen(string $slot = 'app_open'): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.LoadAppOpen', [
            'ad_unit_id' => $this->resolveAdUnitId($slot),
        ]);

        return $this;
    }

    public function showAppOpen(): static
    {
        if (! $this->enabled()) {
            return $this;
        }

        $this->bridgeCall('GoogleMobileAds.ShowAppOpen', []);

        return $this;
    }

    protected function enabled(): bool
    {
        return (bool) config('google-mobile-ads.enabled', true);
    }

    protected function platform(): string
    {
        // NativePHP exposes this as an env var, never a PHP constant — checking
        // defined('NATIVEPHP_PLATFORM') is always false, which silently defaulted
        // every real iOS device to 'android' (only the Simulator branch above it
        // ever matched 'ios'). Mirrors the same env() check the core itself uses
        // (see NativeServiceProvider::class).
        $platform = env('NATIVEPHP_PLATFORM');

        if (in_array($platform, ['ios', 'android'], true)) {
            return $platform;
        }

        // Not running inside a native build (e.g. local `php artisan serve`) —
        // best-effort guess so config/testing still resolves sensibly.
        return str_contains(php_uname('s'), 'Darwin') ? 'ios' : 'android';
    }

    protected function testSlotId(string $slot): string
    {
        // If a raw ID is passed directly, return it even in test mode
        if (str_starts_with($slot, 'ca-app-pub-')) {
            return $slot;
        }

        $testSlots = config('google-mobile-ads.test_slots', []);
        $platform  = $this->platform();

        if (isset($testSlots[$slot][$platform])) {
            return $testSlots[$slot][$platform];
        }

        // Unknown slot name in test mode — return a generic banner test ID
        return $platform === 'ios'
            ? 'ca-app-pub-3940256099942544/2934735716'
            : 'ca-app-pub-3940256099942544/6300978111';
    }

    /**
     * @param  array<string, mixed>  $params
     */
    private function bridgeCall(string $method, array $params): void
    {
        if (function_exists('nativephp_call')) {
            nativephp_call($method, json_encode($params));
        }
    }
}

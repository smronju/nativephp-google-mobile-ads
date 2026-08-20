<?php

namespace NativePHP\GoogleMobileAds\Events;

use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class AdLoaded
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public function __construct(
        public readonly string $adType,
        public readonly string $adUnitId,
        public readonly ?int $heightDp = null,
    ) {}
}

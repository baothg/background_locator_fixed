# Looking for Maintainers
This project is no longer actively maintained. If you are interested in becoming a maintainer, please open an issue or contact me directly !

# background_locator_2 ! [![pub package](https://img.shields.io/pub/v/background_locator_2.svg)](https://pub.dartlang.org/packages/background_locator_2) ![](https://img.shields.io/github/contributors/Yukams/background_locator_fixed) ![](https://img.shields.io/github/license/Yukams/background_locator_fixed)

This package is a V2 of the background_locator package, fixing it and making it work for the newest versions of Flutter. Please read the wiki in order to make this plugin work with flutter 3.x.

A Flutter plugin for getting location updates even when the app is killed.

![demo](https://raw.githubusercontent.com/RomanJos/background_locator/master/demo.gif)

Refer to [wiki](https://github.com/Yukams/background_locator_fixed/wiki) page for install and setup instruction or jump to specific subject with below links:

* [Installation](https://github.com/Yukams/background_locator_fixed/wiki/Installation)
* [Setup](https://github.com/Yukams/background_locator_fixed/wiki/Setup)
* [How to use](https://github.com/Yukams/background_locator_fixed/wiki/How-to-use)
* [Use other plugins in callback](https://github.com/Yukams/background_locator_fixed/wiki/Use-other-plugins-in-callback)
* [Stop on app terminate](https://github.com/Yukams/background_locator_fixed/wiki/Stop-on-app-terminate)
* [LocationSettings options](https://github.com/Yukams/background_locator_fixed/wiki/LocationSettings-options)
* [Restart service on device reboot (Android only)](https://github.com/Yukams/background_locator_fixed/wiki/Restart-service-on-device-reboot)

## Important notes (v2.1.0+)

**All callbacks run in a background isolate**, not the main isolate: the location callback, `initCallback`, `disposeCallback`, `notificationTapCallback` and the error reporter. They cannot touch the UI directly — use `IsolateNameServer` + `SendPort` to forward data to your main isolate.

**Control tracking from the main app isolate.** Call `initialize()` during app startup and await it before registering location updates. Do not recursively initialize or register tracking inside location/init/dispose/error callbacks; the service restores its own dispatcher. `MissingPluginException` means the calling engine/isolate has no registered native handler; it is not proof that a callback ran in the wrong isolate. Plugin availability in a headless engine depends on engine/plugin registration.

**Keep callbacks fast (<10s).** A callback that does not return within 10 seconds trips `BACKGROUND_LOCATOR_TIMEOUT` and is reported to the error reporter. Persist data quickly and return; offload heavy work (network calls, big writes) to a queue processed elsewhere. Repeated errors with the same `code` are deduplicated to one report per 60 seconds.

**`registerLocationUpdate` / `unRegisterLocationUpdate` may throw** `PlatformException` on failure (e.g. `SERVICE_START_FAILED`, `LOCATION_PERMISSION_DENIED`). Wrap them in try/catch instead of assuming success.

**`autoStop` semantics:** when `autoStop: true`, the plugin requests unregister on Flutter's `detached` lifecycle event, not on `paused`. This event is not a reliable swipe-away/process-kill notification and may also occur when the Flutter view is detached. For tracking independent of the UI lifecycle, leave `autoStop: false`. Android force-stop prevents automatic restart until the user launches the app again.

**Android settings validation:** `interval` (seconds) and `wakeLockTime` (minutes) must be positive signed 32-bit integers. `distanceFilter` must be finite, non-negative and fit an Android float. Invalid values now throw `ArgumentError` before the registration channel call; catch these alongside `PlatformException`.

**Android manifest:** the plugin merges location/foreground-service permissions automatically, but the service and boot receiver must still be declared in your app manifest. To get tracking back after reboot and app updates, declare all three actions:

```xml
<receiver
    android:name="yukams.app.background_locator_2.BootBroadcastReceiver"
    android:enabled="true"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

Tracking across process death and reboot requires `ACCESS_BACKGROUND_LOCATION` — a "while in use" grant is not enough to restart from boot.

**`initCallback` data** may contain `wasKilled: true` if the previous tracking session was killed by the OS (process death, crash) instead of a graceful unregister — re-run your cleanup there.

**Error reporting:** `BackgroundLocator.setErrorReporter(fn)` registers a top-level/static function receiving `{source, code, message, stack, timestamp}` for every caught plugin error — including failures that happen while the app is killed (queued, delivered on next dispatcher start). Use it to forward to Crashlytics or your own reporter; pass `null` to unregister.

**OEM battery killers** (Xiaomi/OPPO/vivo/Samsung...) may still kill the foreground service despite `START_STICKY`. If your use case needs reliable background tracking, prompt users to disable battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and enable autostart in OEM settings — this part is app-side UX, the plugin cannot do it.

##  License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details

## Contributor
Thanks to all who contributed on this plugin to fix bugs and adding new feature, including:
* [Rekab](https://github.com/rekabhq) (creator of V1)
* [Gerardo Ibarra](https://github.com/gpibarra)
* [RomanJos](https://github.com/RomanJos)
* [Marcelo Henrique Neppel](https://github.com/marceloneppel)

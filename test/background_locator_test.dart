import 'package:background_locator_2/auto_stop_handler.dart';
import 'package:background_locator_2/settings/android_settings.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:background_locator_2/keys.dart';
import 'package:background_locator_2/location_dto.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('Android settings preserve valid values including large intervals', () {
    final defaults = const AndroidSettings().toMap();
    expect(defaults[Keys.SETTINGS_INTERVAL], 5);
    final large = const AndroidSettings(interval: 3000000).toMap();
    expect(large[Keys.SETTINGS_INTERVAL], 3000000);
  });

  test('Android settings reject invalid provider inputs', () {
    for (final interval in [0, -1, 0x80000000]) {
      expect(
        () => AndroidSettings(interval: interval).toMap(),
        throwsArgumentError,
      );
    }
    for (final distance in [-1.0, double.nan, double.infinity, 1e39]) {
      expect(
        () => AndroidSettings(distanceFilter: distance).toMap(),
        throwsArgumentError,
      );
    }
    for (final minutes in [0, -1, 0x80000000]) {
      expect(
        () => AndroidSettings(wakeLockTime: minutes).toMap(),
        throwsArgumentError,
      );
    }
  });

  testWidgets('autoStop ignores backgrounding and reports stop failures', (
    tester,
  ) async {
    const channel = MethodChannel(Keys.CHANNEL_ID);
    var calls = 0;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      calls++;
      expect(call.method, Keys.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE);
      throw PlatformException(code: 'STOP_FAILED');
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    final observer = AutoStopHandler();
    for (final state in [
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      await observer.didChangeAppLifecycleState(state);
    }
    expect(calls, 0);
    await observer.didChangeAppLifecycleState(AppLifecycleState.detached);
    expect(calls, 1);
    expect(tester.takeException(), isA<PlatformException>());
  });

  test('decodes numeric location fields consistently', () {
    final location = LocationDto.fromJson({
      Keys.ARG_LATITUDE: 47,
      Keys.ARG_LONGITUDE: -122.5,
      Keys.ARG_ACCURACY: 3,
      Keys.ARG_ALTITUDE: 10.25,
      Keys.ARG_SPEED: 1,
      Keys.ARG_SPEED_ACCURACY: 0.5,
      Keys.ARG_HEADING: 90,
      Keys.ARG_TIME: 123456789,
      Keys.ARG_IS_MOCKED: true,
      Keys.ARG_PROVIDER: 'gps',
    });

    expect(location.latitude, 47.0);
    expect(location.longitude, -122.5);
    expect(location.accuracy, 3.0);
    expect(location.altitude, 10.25);
    expect(location.speed, 1.0);
    expect(location.speedAccuracy, 0.5);
    expect(location.heading, 90.0);
    expect(location.time, 123456789.0);
    expect(location.provider, 'gps');
  });

  test('uses safe defaults when optional location data is missing', () {
    final location = LocationDto.fromJson({
      Keys.ARG_LATITUDE: 'invalid',
      Keys.ARG_LONGITUDE: null,
    });

    expect(location.latitude, 0.0);
    expect(location.longitude, 0.0);
    expect(location.provider, '');
  });
}

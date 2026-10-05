import 'dart:io';
import 'dart:ui';

import 'package:background_locator_2/keys.dart';
import 'package:background_locator_2/location_dto.dart';
import 'package:background_locator_2/settings/android_settings.dart';
import 'package:background_locator_2/settings/ios_settings.dart';

class SettingsUtil {
  static Map<String, dynamic> getArgumentsMap({
    required void Function(LocationDto) callback,
    void Function(Map<String, dynamic>)? initCallback,
    Map<String, dynamic>? initDataCallback,
    void Function()? disposeCallback,
    AndroidSettings androidSettings = const AndroidSettings(),
    IOSSettings iosSettings = const IOSSettings(),
  }) {
    final args = _getCommonArgumentsMap(
      callback: callback,
      initCallback: initCallback,
      initDataCallback: initDataCallback,
      disposeCallback: disposeCallback,
    );

    if (Platform.isAndroid) {
      args.addAll(_getAndroidArgumentsMap(androidSettings));
    } else if (Platform.isIOS) {
      args.addAll(_getIOSArgumentsMap(iosSettings));
    }

    return args;
  }

  static Map<String, dynamic> _getCommonArgumentsMap({
    required void Function(LocationDto) callback,
    void Function(Map<String, dynamic>)? initCallback,
    Map<String, dynamic>? initDataCallback,
    void Function()? disposeCallback,
  }) {
    final Map<String, dynamic> args = {
      Keys.ARG_CALLBACK: _rawCallbackHandle(callback),
    };

    if (initCallback != null) {
      args[Keys.ARG_INIT_CALLBACK] = _rawCallbackHandle(initCallback);
    }
    if (disposeCallback != null) {
      args[Keys.ARG_DISPOSE_CALLBACK] = _rawCallbackHandle(disposeCallback);
    }
    if (initDataCallback != null) {
      args[Keys.ARG_INIT_DATA_CALLBACK] = initDataCallback;
    }

    return args;
  }

  static Map<String, dynamic> _getAndroidArgumentsMap(
    AndroidSettings androidSettings,
  ) {
    final Map<String, dynamic> args = {
      Keys.ARG_SETTINGS: androidSettings.toMap(),
    };

    if (androidSettings.androidNotificationSettings.notificationTapCallback !=
        null) {
      args[Keys.ARG_NOTIFICATION_CALLBACK] = _rawCallbackHandle(
        androidSettings.androidNotificationSettings.notificationTapCallback!,
      );
    }

    return args;
  }

  static Map<String, dynamic> _getIOSArgumentsMap(IOSSettings iosSettings) {
    return iosSettings.toMap();
  }

  static int _rawCallbackHandle(Function callback) {
    final handle = PluginUtilities.getCallbackHandle(callback);
    if (handle == null) {
      throw ArgumentError.value(
        callback,
        'callback',
        'Callbacks must be top-level or static functions.',
      );
    }
    return handle.toRawHandle();
  }
}

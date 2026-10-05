import 'dart:async';
import 'dart:ui';

import 'package:background_locator_2/settings/android_settings.dart';
import 'package:background_locator_2/settings/ios_settings.dart';
import 'package:background_locator_2/utils/settings_util.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'auto_stop_handler.dart';
import 'callback_dispatcher.dart';
import 'keys.dart';
import 'location_dto.dart';

class BackgroundLocator {
  static const MethodChannel _channel = const MethodChannel(Keys.CHANNEL_ID);

  static Future<void> initialize() async {
    final CallbackHandle? callback = PluginUtilities.getCallbackHandle(
      callbackDispatcher,
    );
    if (callback == null) {
      throw StateError(
        'Unable to create a callback handle for callbackDispatcher.',
      );
    }
    await _channel.invokeMethod(Keys.METHOD_PLUGIN_INITIALIZE_SERVICE, {
      Keys.ARG_CALLBACK_DISPATCHER: callback.toRawHandle(),
    });
  }

  static AutoStopHandler? _autoStopHandler;

  static Future<void> registerLocationUpdate(
    void Function(LocationDto) callback, {
    void Function(Map<String, dynamic>)? initCallback,
    Map<String, dynamic> initDataCallback = const {},
    void Function()? disposeCallback,
    bool autoStop = false,
    AndroidSettings androidSettings = const AndroidSettings(),
    IOSSettings iosSettings = const IOSSettings(),
  }) async {
    _removeAutoStopObserver();
    if (autoStop) {
      WidgetsFlutterBinding.ensureInitialized().addObserver(
        _autoStopHandler = AutoStopHandler(),
      );
    }

    final args = SettingsUtil.getArgumentsMap(
      callback: callback,
      initCallback: initCallback,
      initDataCallback: initDataCallback,
      disposeCallback: disposeCallback,
      androidSettings: androidSettings,
      iosSettings: iosSettings,
    );

    try {
      await _channel.invokeMethod(
        Keys.METHOD_PLUGIN_REGISTER_LOCATION_UPDATE,
        args,
      );
    } catch (_) {
      _removeAutoStopObserver();
      rethrow;
    }
  }

  static Future<void> unRegisterLocationUpdate() async {
    try {
      await _channel.invokeMethod(
        Keys.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE,
      );
    } finally {
      _removeAutoStopObserver();
    }
  }

  static void _removeAutoStopObserver() {
    final observer = _autoStopHandler;
    if (observer != null) {
      WidgetsBinding.instance.removeObserver(observer);
      _autoStopHandler = null;
    }
  }

  static Future<bool> isRegisterLocationUpdate() async {
    return await _channel.invokeMethod<bool>(
          Keys.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE,
        ) ??
        false;
  }

  static Future<bool> isServiceRunning() async {
    return await _channel.invokeMethod<bool>(
          Keys.METHOD_PLUGIN_IS_SERVICE_RUNNING,
        ) ??
        false;
  }

  static Future<void> updateNotificationText({
    String? title,
    String? msg,
    String? bigMsg,
  }) async {
    final Map<String, dynamic> arg = {};

    if (title != null) {
      arg[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] = title;
    }

    if (msg != null) {
      arg[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG] = msg;
    }

    if (bigMsg != null) {
      arg[Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG] = bigMsg;
    }

    await _channel.invokeMethod(Keys.METHOD_PLUGIN_UPDATE_NOTIFICATION, arg);
  }
}

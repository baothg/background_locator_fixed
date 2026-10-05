import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'keys.dart';
import 'location_dto.dart';

@pragma('vm:entry-point')
void callbackDispatcher() {
  const MethodChannel _backgroundChannel = MethodChannel(
    Keys.BACKGROUND_CHANNEL_ID,
  );
  WidgetsFlutterBinding.ensureInitialized();

  _backgroundChannel.setMethodCallHandler((MethodCall call) async {
    if (Keys.BCM_SEND_LOCATION == call.method) {
      final Map<dynamic, dynamic> args = call.arguments;
      final rawHandle = args[Keys.ARG_CALLBACK];
      if (rawHandle is! int) {
        return;
      }
      final Function? callback = PluginUtilities.getCallbackFromHandle(
        CallbackHandle.fromRawHandle(rawHandle),
      );
      final LocationDto location = LocationDto.fromJson(
        args[Keys.ARG_LOCATION],
      );
      if (callback != null) {
        await callback(location);
      }
    } else if (Keys.BCM_NOTIFICATION_CLICK == call.method) {
      final Map<dynamic, dynamic> args = call.arguments;
      final Function? notificationCallback = _getCallbackFromArguments(
        args,
        Keys.ARG_NOTIFICATION_CALLBACK,
      );
      if (notificationCallback != null) {
        await notificationCallback();
      }
    } else if (Keys.BCM_INIT == call.method) {
      final Map<dynamic, dynamic> args = call.arguments;
      final Function? initCallback = _getCallbackFromArguments(
        args,
        Keys.ARG_INIT_CALLBACK,
      );
      final Map<dynamic, dynamic>? data = args[Keys.ARG_INIT_DATA_CALLBACK];
      if (initCallback != null) {
        await initCallback(data);
      }
    } else if (Keys.BCM_DISPOSE == call.method) {
      final Map<dynamic, dynamic> args = call.arguments;
      final Function? disposeCallback = _getCallbackFromArguments(
        args,
        Keys.ARG_DISPOSE_CALLBACK,
      );
      if (disposeCallback != null) {
        await disposeCallback();
      }
    }
  });
  _backgroundChannel.invokeMethod(Keys.METHOD_SERVICE_INITIALIZED);
}

Function? _getCallbackFromArguments(Map<dynamic, dynamic> args, String key) {
  final rawHandle = args[key];
  if (rawHandle is! int) {
    return null;
  }
  return PluginUtilities.getCallbackFromHandle(
    CallbackHandle.fromRawHandle(rawHandle),
  );
}

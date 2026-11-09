import 'package:flutter/material.dart';

import 'background_locator.dart';

class AutoStopHandler extends WidgetsBindingObserver {
  @override
  Future<void> didChangeAppLifecycleState(AppLifecycleState state) async {
    switch (state) {
      case AppLifecycleState.detached:
        try {
          await BackgroundLocator.unRegisterLocationUpdate();
        } catch (error, stack) {
          FlutterError.reportError(
            FlutterErrorDetails(
              exception: error,
              stack: stack,
              library: 'background_locator_2',
              context: ErrorDescription(
                'while automatically stopping location updates',
              ),
            ),
          );
        }
        break;
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
      case AppLifecycleState.inactive:
      case AppLifecycleState.resumed:
        break;
    }
  }
}

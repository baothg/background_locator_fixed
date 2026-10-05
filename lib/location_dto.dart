import 'dart:io' show Platform;

import 'keys.dart';

class LocationDto {
  final double latitude;
  final double longitude;
  final double accuracy;
  final double altitude;
  final double speed;
  final double speedAccuracy;
  final double heading;
  final double time;
  final bool isMocked;
  final String provider;

  LocationDto._(
    this.latitude,
    this.longitude,
    this.accuracy,
    this.altitude,
    this.speed,
    this.speedAccuracy,
    this.heading,
    this.time,
    this.isMocked,
    this.provider,
  );

  factory LocationDto.fromJson(Map<dynamic, dynamic> json) {
    final isLocationMocked =
        Platform.isAndroid && json[Keys.ARG_IS_MOCKED] == true;
    return LocationDto._(
      _asDouble(json[Keys.ARG_LATITUDE]),
      _asDouble(json[Keys.ARG_LONGITUDE]),
      _asDouble(json[Keys.ARG_ACCURACY]),
      _asDouble(json[Keys.ARG_ALTITUDE]),
      _asDouble(json[Keys.ARG_SPEED]),
      _asDouble(json[Keys.ARG_SPEED_ACCURACY]),
      _asDouble(json[Keys.ARG_HEADING]),
      _asDouble(json[Keys.ARG_TIME]),
      isLocationMocked,
      json[Keys.ARG_PROVIDER] is String
          ? json[Keys.ARG_PROVIDER] as String
          : '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      Keys.ARG_LATITUDE: this.latitude,
      Keys.ARG_LONGITUDE: this.longitude,
      Keys.ARG_ACCURACY: this.accuracy,
      Keys.ARG_ALTITUDE: this.altitude,
      Keys.ARG_SPEED: this.speed,
      Keys.ARG_SPEED_ACCURACY: this.speedAccuracy,
      Keys.ARG_HEADING: this.heading,
      Keys.ARG_TIME: this.time,
      Keys.ARG_IS_MOCKED: this.isMocked,
      Keys.ARG_PROVIDER: this.provider,
    };
  }

  @override
  String toString() {
    return 'LocationDto{latitude: $latitude, longitude: $longitude, accuracy: $accuracy, altitude: $altitude, speed: $speed, speedAccuracy: $speedAccuracy, heading: $heading, time: $time, isMocked: $isMocked, provider: $provider}';
  }
}

double _asDouble(dynamic value) => value is num ? value.toDouble() : 0.0;

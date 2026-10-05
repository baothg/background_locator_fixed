import 'package:background_locator_2/keys.dart';
import 'package:background_locator_2/location_dto.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
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

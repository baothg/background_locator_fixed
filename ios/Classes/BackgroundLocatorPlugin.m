#import "BackgroundLocatorPlugin.h"
#import "Globals.h"
#import "Utils/Util.h"
#import "Preferences/PreferencesManager.h"
#import "InitPluggable.h"
#import "DisposePluggable.h"

@implementation BackgroundLocatorPlugin {
    FlutterEngine *_headlessRunner;
    FlutterMethodChannel *_callbackChannel;
    FlutterMethodChannel *_mainChannel;
    NSObject<FlutterPluginRegistrar> *_registrar;
    CLLocationManager *_locationManager;
    CLLocation* _lastLocation;
    NSDictionary *_initialDataDictionary;
    BOOL _registrationPendingAuthorization;
    BOOL _initCallbackCalled;
}

static FlutterPluginRegistrantCallback registerPlugins = nil;
static BackgroundLocatorPlugin *instance = nil;

#pragma mark FlutterPlugin Methods

+ (void)registerWithRegistrar:(nonnull NSObject<FlutterPluginRegistrar> *)registrar {
    @synchronized(self) {
        if (instance == nil) {
            instance = [[BackgroundLocatorPlugin alloc] init:registrar];
            [registrar addApplicationDelegate:instance];
        }
    }
}

+ (void)setPluginRegistrantCallback:(FlutterPluginRegistrantCallback)callback {
    registerPlugins = callback;
}

+ (BackgroundLocatorPlugin *) getInstance {
    return instance;
}

- (void)invokeMethod:(NSString*_Nonnull)method arguments:(id _Nullable)arguments {
    // Return if flutter engine is not ready
    NSString *isolateId = [_headlessRunner isolateId];
    if (_callbackChannel == nil || isolateId == nil) {
        return;
    }
    
    [_callbackChannel invokeMethod:method arguments:arguments];
}

- (void)handleMethodCall:(FlutterMethodCall *)call
                  result:(FlutterResult)result {
    MethodCallHelper *callHelper = [[MethodCallHelper alloc] init];
    [callHelper handleMethodCall:call result:result delegate:self];
}

//https://medium.com/@calvinlin_96474/ios-11-continuous-background-location-update-by-swift-4-12ce3ac603e3
// iOS will launch the app when new location received
- (BOOL)application:(UIApplication *)application
didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    // Check to see if we're being launched due to a location event.
    if (launchOptions[UIApplicationLaunchOptionsLocationKey] != nil) {
        // Restart the headless service.
        [self startLocatorService:[PreferencesManager getCallbackDispatcherHandle]];
        [PreferencesManager setObservingRegion:YES];
    } else if([PreferencesManager isObservingRegion]) {
        [self prepareLocationManager];
        [self removeLocator];
        [PreferencesManager setObservingRegion:NO];
        [_locationManager startUpdatingLocation];
    }
    
    // Note: if we return NO, this vetos the launch of the application.
    return YES;
}

- (void)applicationDidEnterBackground:(UIApplication *)application {
    if ([PreferencesManager isServiceRunning]) {
        [_locationManager startMonitoringSignificantLocationChanges];
    }
}

-(void)applicationWillTerminate:(UIApplication *)application {
    if ([PreferencesManager isStopWithTerminate]) {
        [self removeLocator];
    } else if ([PreferencesManager isServiceRunning]) {
        [self observeRegionForLocation:_lastLocation];
    }
}

- (void) observeRegionForLocation:(CLLocation *)location {
    if (location == nil || !CLLocationCoordinate2DIsValid(location.coordinate) ||
        ![CLLocationManager isMonitoringAvailableForClass:[CLCircularRegion class]]) {
        return;
    }

    CLLocationDistance maximumRadius = _locationManager.maximumRegionMonitoringDistance;
    if (maximumRadius <= 0) {
        return;
    }
    CLLocationDistance radius = MIN(MAX([PreferencesManager getDistanceFilter], 100.0), maximumRadius);
    CLRegion* region = [[CLCircularRegion alloc] initWithCenter:location.coordinate
                                                         radius:radius
                                                     identifier:@"region"];
    region.notifyOnEntry = false;
    region.notifyOnExit = true;
    [_locationManager startMonitoringForRegion:region];
}

- (void) prepareLocationMap:(CLLocation*) location {
    _lastLocation = location;
    NSDictionary<NSString*,NSNumber*>* locationMap = [Util getLocationMap:location];
    
    [self sendLocationEvent:locationMap];
}

#pragma mark LocationManagerDelegate Methods
- (void)locationManager:(CLLocationManager *)manager
     didUpdateLocations:(NSArray<CLLocation *> *)locations {
    if (locations.count > 0) {
        CLLocation* location = [locations objectAtIndex:0];
        [self prepareLocationMap: location];
        if([PreferencesManager isObservingRegion]) {
            [self observeRegionForLocation: location];
            [_locationManager stopUpdatingLocation];
        }
    }
}

- (void)locationManager:(CLLocationManager *)manager didExitRegion:(CLRegion *)region {
    [_locationManager stopMonitoringForRegion:region];
    if ([PreferencesManager isServiceRunning]) {
        [_locationManager startUpdatingLocation];
    }
}

- (void)locationManagerDidChangeAuthorization:(CLLocationManager *)manager API_AVAILABLE(ios(14.0)) {
    [self handleAuthorizationStatus:manager.authorizationStatus];
}

- (void)locationManager:(CLLocationManager *)manager
 didChangeAuthorizationStatus:(CLAuthorizationStatus)status {
    [self handleAuthorizationStatus:status];
}

- (void)handleAuthorizationStatus:(CLAuthorizationStatus)status {
    if (!_registrationPendingAuthorization) {
        return;
    }
    if (status == kCLAuthorizationStatusAuthorizedAlways) {
        _registrationPendingAuthorization = NO;
        [self setServiceRunning:YES];
        [_locationManager startUpdatingLocation];
        [_locationManager startMonitoringSignificantLocationChanges];
        if (!_initCallbackCalled) {
            InitPluggable *initPluggable = [[InitPluggable alloc] init];
            [initPluggable setCallback:[PreferencesManager getCallbackHandle:kInitCallbackKey]];
            [initPluggable onServiceStart:_initialDataDictionary ?: @{}];
            _initCallbackCalled = YES;
        }
    } else if (status == kCLAuthorizationStatusAuthorizedWhenInUse) {
        [_locationManager requestAlwaysAuthorization];
    } else if (status == kCLAuthorizationStatusDenied ||
               status == kCLAuthorizationStatusRestricted) {
        _registrationPendingAuthorization = NO;
        [self setServiceRunning:NO];
    }
}

- (void)locationManager:(CLLocationManager *)manager didFailWithError:(NSError *)error {
    if (error.code == kCLErrorDenied) {
        _registrationPendingAuthorization = NO;
        [self setServiceRunning:NO];
        [_locationManager stopUpdatingLocation];
        [_locationManager stopMonitoringSignificantLocationChanges];
    }
}

#pragma mark LocatorPlugin Methods
- (void) sendLocationEvent: (NSDictionary<NSString*,NSNumber*>*)location {
    NSString *isolateId = [_headlessRunner isolateId];
    if (_callbackChannel == nil || isolateId == nil) {
        return;
    }
    
    NSDictionary *map = @{
                     kArgCallback : @([PreferencesManager getCallbackHandle:kCallbackKey]),
                     kArgLocation: location
                     };
    [_callbackChannel invokeMethod:kBCMSendLocation arguments:map];
}

- (instancetype)init:(NSObject<FlutterPluginRegistrar> *)registrar {
    self = [super init];
    
    _headlessRunner = [[FlutterEngine alloc] initWithName:@"LocatorIsolate" project:nil allowHeadlessExecution:YES];
    _registrar = registrar;
    [self prepareLocationManager];
    
    _mainChannel = [FlutterMethodChannel methodChannelWithName:kChannelId
                                               binaryMessenger:[registrar messenger]];
    [registrar addMethodCallDelegate:self channel:_mainChannel];
    
    _callbackChannel =
    [FlutterMethodChannel methodChannelWithName:kBackgroundChannelId
                                binaryMessenger:[_headlessRunner binaryMessenger] ];
    return self;
}

- (void) prepareLocationManager {
    _locationManager = [[CLLocationManager alloc] init];
    [_locationManager setDelegate:self];
    _locationManager.pausesLocationUpdatesAutomatically = NO;
}

#pragma mark MethodCallHelperDelegate

- (void)startLocatorService:(int64_t)handle {
    [PreferencesManager setCallbackDispatcherHandle:handle];
    FlutterCallbackInformation *info = [FlutterCallbackCache lookupCallbackInformation:handle];
    if (info == nil || registerPlugins == nil) {
        [self setServiceRunning:NO];
        return;
    }

    NSString *entrypoint = info.callbackName;
    NSString *uri = info.callbackLibraryPath;
    if (![_headlessRunner runWithEntrypoint:entrypoint libraryURI:uri]) {
        [self setServiceRunning:NO];
        return;
    }

    // Once our headless runner has been started, we need to register the application's plugins
    // with the runner in order for them to work on the background isolate. `registerPlugins` is
    // a callback set from AppDelegate.m in the main application. This callback should register
    // all relevant plugins (excluding those which require UI).
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        registerPlugins(_headlessRunner);
    });
    [_registrar addMethodCallDelegate:self channel:_callbackChannel];
}

- (void)registerLocator:(int64_t)callback
           initCallback:(int64_t)initCallback
  initialDataDictionary:(NSDictionary*)initialDataDictionary
        disposeCallback:(int64_t)disposeCallback
               settings: (NSDictionary*)settings {
    long accuracyKey = [[settings objectForKey:kSettingsAccuracy] longValue];
    CLLocationAccuracy accuracy = [Util getAccuracy:accuracyKey];
    double distanceFilter = [[settings objectForKey:kSettingsDistanceFilter] doubleValue];
    BOOL showsBackgroundLocationIndicator =
        [[settings objectForKey:kSettingsShowsBackgroundLocationIndicator] boolValue];
    BOOL stopWithTerminate = [[settings objectForKey:kSettingsStopWithTerminate] boolValue];

    _locationManager.desiredAccuracy = accuracy;
    _locationManager.distanceFilter = distanceFilter;
    if (@available(iOS 11.0, *)) {
        _locationManager.showsBackgroundLocationIndicator = showsBackgroundLocationIndicator;
    }
    if (@available(iOS 9.0, *)) {
        _locationManager.allowsBackgroundLocationUpdates = YES;
    }

    [PreferencesManager saveDistanceFilter:distanceFilter];
    [PreferencesManager setStopWithTerminate:stopWithTerminate];
    [PreferencesManager setCallbackHandle:callback key:kCallbackKey];
    InitPluggable *initPluggable = [[InitPluggable alloc] init];
    [initPluggable setCallback:initCallback];
    DisposePluggable *disposePluggable = [[DisposePluggable alloc] init];
    [disposePluggable setCallback:disposeCallback];

    _initialDataDictionary = initialDataDictionary ?: @{};
    _initCallbackCalled = NO;
    _registrationPendingAuthorization = YES;
    [self setServiceRunning:NO];

    CLAuthorizationStatus status;
    if (@available(iOS 14.0, *)) {
        status = _locationManager.authorizationStatus;
    } else {
        status = [CLLocationManager authorizationStatus];
    }
    if (status == kCLAuthorizationStatusNotDetermined) {
        [_locationManager requestAlwaysAuthorization];
    }
    [self handleAuthorizationStatus:status];
}

- (void)removeLocator {
    if (_locationManager == nil) {
        [self setServiceRunning:NO];
        return;
    }

    _registrationPendingAuthorization = NO;
    _initCallbackCalled = NO;
    _initialDataDictionary = nil;
    [self setServiceRunning:NO];
    @synchronized (self) {
        [_locationManager stopUpdatingLocation];
        
        if (@available(iOS 9.0, *)) {
            _locationManager.allowsBackgroundLocationUpdates = NO;
        }
        
        [_locationManager stopMonitoringSignificantLocationChanges];

        for (CLRegion* region in [_locationManager monitoredRegions]) {
            [_locationManager stopMonitoringForRegion:region];
        }
    }
    
    DisposePluggable *disposePluggable = [[DisposePluggable alloc] init];
    [disposePluggable onServiceDispose];
}

- (void) setServiceRunning:(BOOL) value {
    @synchronized(self) {
        [PreferencesManager setServiceRunning:value];
    }
}

- (BOOL)isServiceRunning{
    return [PreferencesManager isServiceRunning];
}

- (BOOL)isStopWithTerminate{
    return [PreferencesManager isStopWithTerminate];
}

@end

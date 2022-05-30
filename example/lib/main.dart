import 'dart:async';
import 'dart:isolate';
import 'dart:math';
import 'dart:ui';

import 'package:background_locator/background_locator.dart';
import 'package:background_locator/location_dto.dart';
import 'package:background_locator/settings/android_settings.dart';
import 'package:background_locator/settings/ios_settings.dart';
import 'package:background_locator/settings/locator_settings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:location_permissions/location_permissions.dart';

import 'file_manager.dart';
import 'location_callback_handler.dart';
import 'location_service_repository.dart';

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_geofence/geofence.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  ReceivePort port = ReceivePort();

  String logStr = '';
  bool isRunning;
  LocationDto lastLocation;

  TextEditingController _controllerLat = TextEditingController();
  TextEditingController _controllerLng = TextEditingController();
  TextEditingController _controllerRad = TextEditingController();

  final dec = BoxDecoration(
      border: Border.all(
        color: Colors.blue[500],
      ),
      borderRadius: BorderRadius.all(Radius.circular(10)));

  final padding = EdgeInsets.only(right: 10, left: 10);

  FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
      new FlutterLocalNotificationsPlugin();

  @override
  void initState() {
    super.initState();

    setDataFromShared();

    initNotificationPlugin();

    initPlatformStateGeoFence();

    if (IsolateNameServer.lookupPortByName(
            LocationServiceRepository.isolateName) !=
        null) {
      IsolateNameServer.removePortNameMapping(
          LocationServiceRepository.isolateName);
    }

    IsolateNameServer.registerPortWithName(
        port.sendPort, LocationServiceRepository.isolateName);

    port.listen(
      (dynamic data) async {
        await updateUI(data);
      },
    );
    initPlatformState();
  }

  @override
  void dispose() {
    super.dispose();
  }

  void initNotificationPlugin() {
    // initialise the plugin. app_icon needs to be a added as a drawable resource to the Android head project
    var initializationSettingsAndroid =
        new AndroidInitializationSettings('@mipmap/ic_location');
    var initializationSettingsIOS =
        IOSInitializationSettings(onDidReceiveLocalNotification: null);
    var initializationSettings = InitializationSettings(
        android: initializationSettingsAndroid, iOS: initializationSettingsIOS);
    flutterLocalNotificationsPlugin.initialize(initializationSettings,
        onSelectNotification: null);
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> initPlatformStateGeoFence() async {
    // If the widget was removed from the tree while the asynchronous platform
    // message was in flight, we want to discard the reply rather than calling
    // setState to update our non-existent appearance.
    if (!mounted) return;
    Geofence.initialize();
    Geofence.startListening(GeolocationEvent.entry, (entry) {
      print('*****entry');
      scheduleNotification("Entry of a geo zone", "Welcome to: ${entry.id}");
    });

    Geofence.startListening(GeolocationEvent.exit, (entry) {
      print('******exit');
      scheduleNotification("Exit of a geo zone", "Bye bye to: ${entry.id}");
    });

    setState(() {});
  }

  void scheduleNotification(String title, String subtitle) {
    print("scheduling one with $title and $subtitle");
    var rng = new Random();
    Future.delayed(Duration(seconds: 5)).then((result) async {
      var androidPlatformChannelSpecifics = AndroidNotificationDetails(
          'bg_locator', 'background locate',
          importance: Importance.high,
          priority: Priority.high,
          ticker: 'ticker');
      var iOSPlatformChannelSpecifics = IOSNotificationDetails();
      var platformChannelSpecifics = NotificationDetails(
          android: androidPlatformChannelSpecifics,
          iOS: iOSPlatformChannelSpecifics);
      await flutterLocalNotificationsPlugin.show(
          rng.nextInt(100000), title, subtitle, platformChannelSpecifics,
          payload: 'item x');
    });
  }

  Future<void> updateUI(LocationDto data) async {
    final log = await FileManager.readLogFile();

    await _updateNotificationText(data);

    setState(() {
      if (data != null) {
        lastLocation = data;
      }
      logStr = log;
    });
  }

  Future<void> _updateNotificationText(LocationDto data) async {
    if (data == null) {
      return;
    }

    await BackgroundLocator.updateNotificationText(
        title: "new location received",
        msg: "${DateTime.now()}",
        bigMsg: "${data.latitude}, ${data.longitude}");
  }

  Future<void> initPlatformState() async {
    print('Initializing...');
    await BackgroundLocator.initialize();
    logStr = await FileManager.readLogFile();
    print('Initialization done');
    final _isRunning = await BackgroundLocator.isServiceRunning();
    setState(() {
      isRunning = _isRunning;
    });
    print('Running ${isRunning.toString()}');
  }

  @override
  Widget build(BuildContext context) {
    final start = SizedBox(
      width: double.maxFinite,
      child: ElevatedButton(
        child: Text('Start'),
        onPressed: () {
          _onStart();
        },
      ),
    );
    final stop = SizedBox(
      width: double.maxFinite,
      child: ElevatedButton(
        child: Text('Stop'),
        onPressed: () {
          onStop();
        },
      ),
    );
    final clear = SizedBox(
      width: double.maxFinite,
      child: ElevatedButton(
        child: Text('Clear Log'),
        onPressed: () {
          FileManager.clearLogFile();
          setState(() {
            logStr = '';
          });
        },
      ),
    );
    String msgStatus = "-";
    if (isRunning != null) {
      if (isRunning) {
        msgStatus = 'Is running';
      } else {
        msgStatus = 'Is not running';
      }
    }
    final status = Text("Status: $msgStatus");

    final log = Text(
      logStr,
    );

    final textWidget1 = Container(
        width: 200,
        height: 50,
        margin: padding,
        decoration: dec,
        child: TextField(
          controller: _controllerLat,
          keyboardType:
              TextInputType.numberWithOptions(signed: true, decimal: true),
          maxLines: 1,
          decoration: new InputDecoration(
              border: InputBorder.none,
              contentPadding:
                  EdgeInsets.only(left: 15, bottom: 11, top: 11, right: 15),
              hintText: "Lat"),
        ));

    final textWidget2 = Container(
        width: 200,
        height: 50,
        margin: padding,
        decoration: dec,
        child: TextField(
            controller: _controllerLng,
            keyboardType:
                TextInputType.numberWithOptions(signed: true, decimal: true),
            maxLines: 1,
            decoration: new InputDecoration(
                border: InputBorder.none,
                contentPadding:
                    EdgeInsets.only(left: 15, bottom: 11, top: 11, right: 15),
                hintText: "Lng")));

    final textWidget3 = Container(
        width: 200,
        height: 50,
        margin: padding,
        decoration: dec,
        child: TextField(
            controller: _controllerRad,
            keyboardType:
                TextInputType.numberWithOptions(signed: true, decimal: true),
            maxLines: 1,
            decoration: new InputDecoration(
                border: InputBorder.none,
                contentPadding:
                    EdgeInsets.only(left: 15, bottom: 11, top: 11, right: 15),
                hintText: "Radius in meter")));

    final raw = Container(
      height: 50,
      child: ListView(
        children: [textWidget1, textWidget2, textWidget3],
        scrollDirection: Axis.horizontal,
      ),
    );

    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Flutter background Locator'),
        ),
        body: Container(
          width: double.maxFinite,
          padding: const EdgeInsets.all(22),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: <Widget>[start, stop, clear, raw, status, log],
            ),
          ),
        ),
      ),
    );
  }

  void onStop() async {
    await BackgroundLocator.unRegisterLocationUpdate();
    final _isRunning = await BackgroundLocator.isServiceRunning();
    setState(() {
      isRunning = _isRunning;
    });

    Geofence.removeAllGeolocations();
    Geofence.stopListeningForLocationChanges();
  }

  void _onStart() async {
    if (await _checkLocationPermission()) {
      await _startLocator();
      final _isRunning = await BackgroundLocator.isServiceRunning();

      setState(() {
        isRunning = _isRunning;
        lastLocation = null;
      });

      if (_controllerRad.text.isNotEmpty &&
          _controllerLat.text.isNotEmpty &&
          _controllerLng.text.isNotEmpty) {
        startGeoFence();
      }
    } else {
      // show error
    }
  }

  Future<bool> _checkLocationPermission() async {
    final access = await LocationPermissions().checkPermissionStatus();
    switch (access) {
      case PermissionStatus.unknown:
      case PermissionStatus.denied:
      case PermissionStatus.restricted:
        final permission = await LocationPermissions().requestPermissions(
          permissionLevel: LocationPermissionLevel.locationAlways,
        );
        if (permission == PermissionStatus.granted) {
          return true;
        } else {
          return false;
        }
        break;
      case PermissionStatus.granted:
        return true;
        break;
      default:
        return false;
        break;
    }
  }

  Future<void> _startLocator() async {
    Map<String, dynamic> data = {'countInit': 1};
    return await BackgroundLocator.registerLocationUpdate(
        LocationCallbackHandler.callback,
        initCallback: LocationCallbackHandler.initCallback,
        initDataCallback: data,
        disposeCallback: LocationCallbackHandler.disposeCallback,
        iosSettings: IOSSettings(
            accuracy: LocationAccuracy.NAVIGATION, distanceFilter: 0),
        autoStop: false,
        androidSettings: AndroidSettings(
            accuracy: LocationAccuracy.NAVIGATION,
            interval: 5,
            distanceFilter: 0,
            client: LocationClient.google,
            androidNotificationSettings: AndroidNotificationSettings(
                notificationChannelName: 'Location tracking',
                notificationTitle: 'Start Location Tracking',
                notificationMsg: 'Track location in background',
                notificationBigMsg:
                    'Background location is on to keep the app up-tp-date with your location. This is required for main features to work properly when the app is not running.',
                notificationIconColor: Colors.grey,
                notificationTapCallback:
                    LocationCallbackHandler.notificationCallback)));
  }

  Future<void> writeData(var lat, var lng, var radius) async {
    // Obtain shared preferences.
    final prefs = await SharedPreferences.getInstance();

    if (lat == null) {
      lat = "0";
    }

    if (lng == null) {
      lng = "0";
    }

    if (radius == null) {
      radius = "0";
    }

    lat = double.parse(lat);
    lng = double.parse(lng);
    radius = double.parse(radius);

    await prefs.setDouble('lat', lat);
    await prefs.setDouble('lng', lng);
    await prefs.setDouble('rad', radius);
  }

  Future<void> setDataFromShared() async {
    final prefs = await SharedPreferences.getInstance();

    double lat = prefs.getDouble('lat') ?? 0;
    double lng = prefs.getDouble('lng') ?? 0;
    double rad = prefs.getDouble('rad') ?? 0;

    _controllerLat.text = lat.toString();
    _controllerLng.text = lng.toString();
    _controllerRad.text = rad.toString();
  }

  Future<void> startGeoFence() async {
    FocusScope.of(context).unfocus();

    writeData(_controllerLat.text.trim(), _controllerLng.text.trim(),
        _controllerRad.text.trim());

    final prefs = await SharedPreferences.getInstance();

    double lat = prefs.getDouble('lat') ?? 0;
    double lng = prefs.getDouble('lng') ?? 0;
    double rad = prefs.getDouble('rad') ?? 0;

    print('lat $lat, lng $lng rad $rad');

    if (lat != 0 && lng != 0 && rad != 0) {
      Geolocation location =
          Geolocation(latitude: lat, longitude: lng, radius: rad, id: "Loc1");

      Geofence.addGeolocation(location, GeolocationEvent.entry).then((onValue) {
        scheduleNotification(
            "Geo region added", "Your geo point has been added!");
      }).catchError((onError) {
        print("great failure");
      });
      Geofence.startListeningForLocationChanges();
    }
  }
}

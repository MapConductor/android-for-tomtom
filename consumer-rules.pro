# GeoPoint クラスとそのメンバーを保持
-keep class com.mapconductor.tomtom.GeoPoint { *; }
# GeoPoint のコンパニオンオブジェクト内のメソッドも保持する場合（例: fromLatLng）
-keepclassmembers class com.mapconductor.tomtom.GeoPoint$Companion {
    public static com.mapconductor.tomtom.GeoPoint fromLatLong(double, double);
    public static com.mapconductor.tomtom.GeoPoint fromLongLat(double, double);
}

# MapCameraPosition クラスとそのメンバーを保持
-keep class com.mapconductor.tomtom.MapCameraPosition { *; }

-keepclassmembers class com.mapconductor.tomtom.MapCameraPosition {
   public com.mapconductor.tomtom.GeoPoint target;
   public double zoom;
   public double bearing;
   public double tilt;
}

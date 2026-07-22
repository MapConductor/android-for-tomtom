# MapConductor TomTom ProGuard Rules

# Keep line number information for debugging
-keepattributes SourceFile,LineNumberTable

# Keep all public API classes
-keep public class com.mapconductor.tomtom.** { public *; }

# Keep TomTom specific implementations
-keep class com.mapconductor.tomtom.TomTomMapViewController { *; }
-keep class com.mapconductor.tomtom.TomTomMapView { *; }

# Keep marker implementations
-keep class com.mapconductor.tomtom.marker.** { *; }

# Keep TomTom SDK classes
-keep class com.tomtom.sdk.** { *; }

# Compose integration
-keep class * extends androidx.compose.runtime.** { *; }

# Fix for Java 11+ StringConcatFactory issue
-dontwarn java.lang.invoke.StringConcatFactory
-keep class java.lang.invoke.StringConcatFactory { *; }

-keep class com.laguku.player.LagukuApp { *; }
-keep class com.laguku.player.MainActivity { *; }
-keep class com.laguku.player.service.PlaybackService { *; }
-keep class com.laguku.player.service.AlarmReceiver { *; }
-keep class com.laguku.player.service.LagukuTileService { *; }
-keep class com.laguku.player.widget.LagukuPillWidgetProvider { *; }
-keep class androidx.core.content.FileProvider { *; }

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

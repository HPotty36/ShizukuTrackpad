# Keep Shizuku UserService entry point and its constructors
-keep class com.shizukutrackpad.app.service.InputInjectorService {
    public <init>();
    public <init>(android.content.Context);
}

# Keep generated AIDL interface and Stub
-keep class com.shizukutrackpad.app.IInputInjectorService { *; }
-keep class com.shizukutrackpad.app.IInputInjectorService$* { *; }

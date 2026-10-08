# Keep generic signatures
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Room：实体和 DAO 靠反射 new 实例，混淆字段/构造会全查不到
-keep class com.dafeng.moneynote.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Hilt / Dagger：生成的类靠反射查找
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * {
    @dagger.* <methods>;
}
-keep class * extends androidx.hilt.** { *; }

# Glance 桌面小组件：WidgetReceiver / UpdateWorker 靠反射拉起
-keep class com.dafeng.moneynote.widget.** { *; }

# App 自己的实体/序列化类
-keep class com.dafeng.moneynote.data.remote.** { *; }

# ML Kit / OCR 回调
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dafeng.moneynote.**$$serializer { *; }
-keepclassmembers class com.dafeng.moneynote.** {
    *** Companion;
}
-keepclasseswithmembers class com.dafeng.moneynote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 序列化靠字段名匹配，字段名不能被改
-keepclassmembers class com.dafeng.moneynote.** {
    <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

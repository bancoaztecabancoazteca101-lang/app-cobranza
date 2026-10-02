# Estrategia SEGURA para campo: no se ofusca (los errores salen legibles) y TODO el código propio se
# conserva tal cual. R8 solo reduce y optimiza las LIBRERÍAS (p. ej. material-icons-extended, que
# pesa decenas de MB y casi no se usa) → APK mucho más chico, arranque y scroll más rápidos.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions
-keep class com.example.matrizapp.** { *; }

# Google API client / Sheets / Drive: el JSON se llena por reflexión sobre campos @Key
-keep class com.google.api.** { *; }
-keep class com.google.api.client.** { *; }
-keepclassmembers class * { @com.google.api.client.util.Key <fields>; }
-keepclassmembers class * { @com.google.api.client.util.Value <fields>; }
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keep class * extends com.google.api.client.util.GenericData { *; }

# Referencias opcionales que no existen en Android
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.misc.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.api.client.http.apache.**
-dontwarn com.google.api.client.extensions.appengine.**
-dontwarn com.google.appengine.api.**

# Numéros de ligne conservés : les traces d'erreur des logs restent lisibles
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Socket.IO / Engine.IO : bibliothèque sans règles R8 fournies, on la garde entière par prudence
# (OkHttp, WorkManager et AndroidX embarquent leurs propres règles)
-keep class io.socket.** { *; }
-dontwarn io.socket.**

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**

# sshj lädt Cipher-/KeyExchange-/Signature-Implementierungen reflektiv
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
# net.i2p.crypto.eddsa referenziert JVM-interne Klassen, die es auf Android nicht gibt
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn sun.security.x509.X509Key

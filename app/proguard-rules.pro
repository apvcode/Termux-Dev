# keep defaults
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.gemalto.jp2.JP2Decoder
# apksig encodes certificates and signatures with an ASN.1 encoder that finds fields by their annotations.
-keep class com.android.apksig.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

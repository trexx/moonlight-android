# Don't obfuscate code. Stack traces from user crash reports have to be readable without a
# mapping file, since builds are made from source by anyone running this fork.
-dontobfuscate

# Our code
# The USB drivers are reached from native code by name, so R8 cannot see those references: the
# xow driver calls back into XboxWirelessDongle and GipController, resolves GipCrypto's statics,
# and binds its own entry points against the method names in these classes from the tables in
# xow_driver_jni.cpp.
-keep class com.limelight.binding.input.driver.* {*;}

# Moonlight common
# MoonBridge's methods are resolved by name from JNI; shrinking them breaks every callback.
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
# JCA providers are instantiated reflectively by algorithm name, so nothing here is reachable
# through a call graph R8 can follow.
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

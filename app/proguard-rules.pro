# SPDX-FileCopyrightText: The pasejo Authors
# SPDX-License-Identifier: 0BSD

# The bindings call into the native library through JNA, which reflects on
# these classes.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class wtf.metio.pasejo.core.** { *; }
-dontwarn java.awt.**

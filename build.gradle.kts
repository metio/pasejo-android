// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

plugins {
    id("com.android.application") version "9.5.1" apply false
    // The Compose compiler must match the Kotlin version that AGP compiles with.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

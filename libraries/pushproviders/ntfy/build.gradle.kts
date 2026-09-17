import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.libraries.pushproviders.ntfy"
}

setupDependencyInjection()

dependencies {
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.core)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.di)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.pushproviders.api)
    implementation(projects.libraries.pushstore.api)
    implementation(projects.libraries.troubleshoot.api)
    implementation(projects.services.toolbox.api)

    // Foreground service and its persistent notification.
    implementation(libs.androidx.core)

    // WebSocket used to subscribe to the ntfy topic.
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp.okhttp)

    // Payload parsing.
    implementation(libs.serialization.json)

    implementation(libs.coroutines.core)

    testCommonDependencies(libs)
}

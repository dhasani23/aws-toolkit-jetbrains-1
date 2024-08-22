// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.model

import java.io.File

sealed class LocalBuildResult {
    data class Success(val dependencyDirectory: File?) : LocalBuildResult()
    data class Failure(val failureReason: String) : LocalBuildResult()
    data object Cancelled : LocalBuildResult()
}

// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.commands

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerJobCompletedResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.DownloadFailureReason
import software.aws.toolkits.jetbrains.services.codemodernizer.model.LocalBuildResult

class CodeTransformMessageListener {

    private val _messages by lazy { MutableSharedFlow<CodeTransformActionMessage>(extraBufferCapacity = 10) }
    val flow = _messages.asSharedFlow()

    fun onStartingHil() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.StartHil))
    }

    fun onStopClicked() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.StopClicked))
    }

    fun onTransformStopped() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.TransformStopped))
    }

    fun onLocalBuildResult(result: LocalBuildResult) {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.LocalBuildComplete, localBuildResult = result))
    }

    fun onUploadResult() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.UploadComplete))
    }

    fun onTransformResult(result: CodeModernizerJobCompletedResult) {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.TransformComplete, transformResult = result))
    }

    fun onTransformResuming() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.TransformResuming))
    }

    fun onDownloadFailure(failure: DownloadFailureReason) {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.DownloadFailed, downloadFailure = failure))
    }

    fun onAuthRestored() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.AuthRestored))
    }

    fun onCheckAuth() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.CheckAuth))
    }

    fun onReauthStarted() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.ReauthStarted))
    }

    // provide singleton access
    companion object {
        val instance = CodeTransformMessageListener()
    }
}

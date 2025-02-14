/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.dagger

import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.unfold.SysUIUnfoldModule.BoundFromSysUiUnfoldModule
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import java.util.Optional
import kotlin.jvm.optionals.getOrElse


/**
 * Module for foldable-related classes that is available in all SystemUI variants.
 * Provides `Optional<SysUIUnfoldComponent>` which is present when the device is a foldable
 * device that has fold/unfold animation enabled.
 */
@Module
abstract class CommonSystemUIUnfoldModule {

    /* Note this will be injected as @BoundFromSysUiUnfoldModule Optional<Optional<...>> */
    @BindsOptionalOf
    @BoundFromSysUiUnfoldModule
    abstract fun optionalSysUiUnfoldComponent(): Optional<SysUIUnfoldComponent>

    companion object {
        @Provides
        @SysUISingleton
        fun sysUiUnfoldComponent(
            /**
             * This will be empty when [com.android.systemui.unfold.SysUIUnfoldModule] is not part
             * of the graph, and contain the optional when it is.
             */
            @BoundFromSysUiUnfoldModule
            optionalOfOptional: Optional<Optional<SysUIUnfoldComponent>>
        ): Optional<SysUIUnfoldComponent> = optionalOfOptional.getOrElse { Optional.empty() }
    }
}
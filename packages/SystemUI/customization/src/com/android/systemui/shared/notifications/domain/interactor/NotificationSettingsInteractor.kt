/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shared.notifications.domain.interactor

import com.android.systemui.shared.notifications.data.repository.NotificationSettingsRepository
import com.android.systemui.shared.notifications.shared.model.NotificationSettingsModel
import kotlinx.coroutines.flow.Flow

/** Encapsulates business logic for interacting with notification settings. */
class NotificationSettingsInteractor(
    private val repository: NotificationSettingsRepository,
) {
    /** The current state of the notification setting. */
    val settings: Flow<NotificationSettingsModel> = repository.settings

    /** Toggles the setting to show or hide notifications on the lock screen. */
    suspend fun toggleShowNotificationsOnLockScreenEnabled() {
        val currentModel = repository.getSettings()
        setSettings(
            currentModel.copy(
                isShowNotificationsOnLockScreenEnabled =
                    !currentModel.isShowNotificationsOnLockScreenEnabled,
            )
        )
    }

    suspend fun setSettings(model: NotificationSettingsModel) {
        repository.setSettings(model)
    }

    suspend fun getSettings(): NotificationSettingsModel {
        return repository.getSettings()
    }
}

/*
 * Copyright 2020-2021 Project 404
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
package com.android.settings.deviceinfo.firmwareversion

import com.android.settings.R;
import android.os.SystemProperties
import android.content.Context
import com.android.settings.core.BasePreferenceController

class p404VersionPreferenceController(private val mContext: Context, key: String?) :
    BasePreferenceController(mContext, key) {
    override public fun getAvailabilityStatus() = AVAILABLE_UNSEARCHABLE
    override public fun getSummary() = SystemProperties.get(
            PROPERTY_P404_PROP,
            mContext.getString(R.string.unknown)
        )

    companion object {
        private const val PROPERTY_P404_PROP = "ro.404.version_code"
    }
}

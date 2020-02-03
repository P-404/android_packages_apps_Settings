/*
 * Copyright (C) 2020 shagbag913
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.display;

import android.content.Context;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.UserHandle;

import com.android.settings.core.BasePreferenceController;

public class PulseOnNewTracksPreferenceController extends BasePreferenceController {

    private AmbientDisplayConfiguration mConfig;

    public PulseOnNewTracksPreferenceController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    public PulseOnNewTracksPreferenceController setConfig(AmbientDisplayConfiguration config) {
        mConfig = config;
        return this;
    }

    @Override
    public int getAvailabilityStatus() {
        return getAmbientConfig().pulseOnNotificationAvailable()
                ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    private AmbientDisplayConfiguration getAmbientConfig() {
        if (mConfig == null) {
            mConfig = new AmbientDisplayConfiguration(mContext);
        }

        return mConfig;
    }
}


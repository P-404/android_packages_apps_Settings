/*
 * Copyright (C) 2021 Wave-OS
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

package com.android.settings.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.SwitchPreference;

import com.android.internal.logging.nano.MetricsProto;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import com.p404.support.preferences.SystemSettingMasterSwitchPreference;

public class NetworkSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "Network Settings";
    private static final String KEY_NETWORK_TRAFFIC = "network_traffic_state";

    private SystemSettingMasterSwitchPreference mNetworkTraffic;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.network_and_internet);
        addPreferencesFromResource(R.xml.network_provider_internet);
        PreferenceScreen prefSet = getPreferenceScreen();
        final ContentResolver resolver = getActivity().getContentResolver();

        mNetworkTraffic = (SystemSettingMasterSwitchPreference)
                findPreference(KEY_NETWORK_TRAFFIC);
        boolean enabled = Settings.System.getIntForUser(resolver,
                KEY_NETWORK_TRAFFIC, 0, UserHandle.USER_CURRENT) == 1;
        mNetworkTraffic.setChecked(enabled);
        mNetworkTraffic.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        ContentResolver resolver = getActivity().getContentResolver();
        if (preference == mNetworkTraffic) {
            boolean value = (Boolean) newValue;
            Settings.System.putIntForUser(resolver, KEY_NETWORK_TRAFFIC,
                    value ? 1 : 0, UserHandle.USER_CURRENT);
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
       return MetricsProto.MetricsEvent.ERROR;
    }
}

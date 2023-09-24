/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settings.bluetooth;

import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.provider.DeviceConfig;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.settings.bluetooth.GroupBluetoothProfileSwitchConfirmDialog.BluetoothProfileConfirmListener;

import com.android.settings.R;
import com.android.settings.core.SettingsUIDeviceConfig;
import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.HeadsetProfile;
import com.android.settingslib.bluetooth.LeAudioProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.bluetooth.MapProfile;
import com.android.settingslib.bluetooth.PanProfile;
import com.android.settingslib.bluetooth.PbapServerProfile;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class adds switches for toggling the individual profiles that a Bluetooth device
 * supports, such as "Phone audio", "Media audio", "Contact sharing", etc.
 */
public class BluetoothDetailsProfilesController extends BluetoothDetailsController
        implements Preference.OnPreferenceClickListener,
        LocalBluetoothProfileManager.ServiceListener, BluetoothCallback,
        BluetoothProfileConfirmListener {
    private static final String TAG = "BtDetailsProfilesCtrl";

    private static final String KEY_PROFILES_GROUP = "bluetooth_profiles";
    private static final String KEY_BOTTOM_PREFERENCE = "bottom_preference";
    private static final String BLUETOOTH_PROFILE_CONFIRM_DIALOG_PROP =
             "persist.vendor.service.bt.profile_confirm_dialog";
    private static final int ORDINAL = 99;

    @VisibleForTesting
    static final String HIGH_QUALITY_AUDIO_PREF_TAG = "A2dpProfileHighQualityAudio";

    private LocalBluetoothManager mManager;
    private LocalBluetoothProfileManager mProfileManager;
    private CachedBluetoothDevice mCachedDevice;
    private List<CachedBluetoothDevice> mAllOfCachedDevices;
    private Map<String, List<CachedBluetoothDevice>> mProfileDeviceMap =
            new HashMap<String, List<CachedBluetoothDevice>>();
    private boolean mIsLeContactSharingEnabled = false;

    @VisibleForTesting
    PreferenceCategory mProfilesContainer;

    private PreferenceFragmentCompat mFragment;
    private boolean mIsGroupDevice = false;
    private GroupBluetoothProfileSwitchConfirmDialog mGroupBluetoothProfileConfirm;
    private int mGroupId = -1;
    private GroupUtils mGroupUtils;
    private LocalBluetoothProfile mProfile;
    private SwitchPreference mProfilePref;
    private boolean mIsProfileConfirmDialogSupported = false;

    public BluetoothDetailsProfilesController(Context context, PreferenceFragmentCompat fragment,
            LocalBluetoothManager manager, CachedBluetoothDevice device, Lifecycle lifecycle) {
        super(context, fragment, device, lifecycle);
        mManager = manager;
        mProfileManager = mManager.getProfileManager();
        mCachedDevice = device;
        mAllOfCachedDevices = getAllOfCachedBluetoothDevices();
        lifecycle.addObserver(this);
        mFragment = fragment;
        mGroupUtils = new GroupUtils(context);
        mIsGroupDevice = mGroupUtils.isGroupDevice(mCachedDevice);
        if (mIsGroupDevice) {
            mGroupId = mGroupUtils.getGroupId(mCachedDevice);
        }
        mIsProfileConfirmDialogSupported =
                SystemProperties.getBoolean(BLUETOOTH_PROFILE_CONFIRM_DIALOG_PROP, false);
    }

    @Override
    protected void init(PreferenceScreen screen) {
        mProfilesContainer = (PreferenceCategory)screen.findPreference(getPreferenceKey());
        mProfilesContainer.setLayoutResource(R.layout.preference_bluetooth_profile_category);
        mIsLeContactSharingEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SETTINGS_UI,
                SettingsUIDeviceConfig.BT_LE_AUDIO_CONTACT_SHARING_ENABLED, true);
        // Call refresh here even though it will get called later in onResume, to avoid the
        // list of switches appearing to "pop" into the page.
        refresh();
    }

    /**
     * Creates a switch preference for the particular profile.
     *
     * @param context The context to use when creating the SwitchPreference
     * @param profile The profile for which the preference controls.
     * @return A preference that allows the user to choose whether this profile
     * will be connected to.
     */
    private SwitchPreference createProfilePreference(Context context,
            LocalBluetoothProfile profile) {
        SwitchPreference pref = new SwitchPreference(context);
        pref.setKey(profile.toString());
        pref.setTitle(profile.getNameResource(mCachedDevice.getDevice()));
        pref.setOnPreferenceClickListener(this);
        pref.setOrder(profile.getOrdinal());
        return pref;
    }

    /**
     * Refreshes the state for an existing SwitchPreference for a profile.
     * If the LeAudio profile is enabled on the LeAudio devices, then the SwitchPreferences of
     * A2dp profile and Headset profile are graied out.
     */
    private void refreshProfilePreference(SwitchPreference profilePref,
            LocalBluetoothProfile profile) {
        BluetoothDevice device = mCachedDevice.getDevice();
        boolean isLeAudioEnabled = isLeAudioEnabled();
        if (profile instanceof A2dpProfile
                || profile instanceof HeadsetProfile) {
            if (isLeAudioEnabled) {
                // If the LeAudio profile is enabled on the LeAudio devices, then the
                // SwitchPreferences of A2dp profile and Headset profile are grayed out.
                Log.d(TAG, "LE is enabled, gray out " + profile.toString());
                profilePref.setEnabled(false);
            } else {
                List<CachedBluetoothDevice> deviceList = mProfileDeviceMap.get(
                        profile.toString());
                boolean isBusy = deviceList != null
                        && deviceList.stream().anyMatch(item -> item.isBusy());
                profilePref.setEnabled(!isBusy);
            }
        } else if (profile instanceof LeAudioProfile) {
            List<CachedBluetoothDevice> leAudioDeviceList = mProfileDeviceMap.get(
                    profile.toString());
            boolean isBusy = leAudioDeviceList != null
                    && leAudioDeviceList.stream().anyMatch(item -> item.isBusy());
            if (isLeAudioEnabled && !isBusy) {
                LocalBluetoothProfile a2dp = mProfileManager.getA2dpProfile();
                LocalBluetoothProfile headset = mProfileManager.getHeadsetProfile();
                // If the LeAudio profile is enabled on the LeAudio devices, then the
                // SwitchPreferences of A2dp profile and Headset profile are graied out.
                grayOutPreferenceWhenLeAudioIsEnabled(a2dp);
                grayOutPreferenceWhenLeAudioIsEnabled(headset);
            }
            profilePref.setEnabled(!isBusy);
        } else if (profile instanceof PbapServerProfile
                && isLeAudioEnabled
                && !mIsLeContactSharingEnabled) {
            profilePref.setEnabled(false);
        } else {
            profilePref.setEnabled(!mCachedDevice.isBusy());
        }

        if (profile instanceof MapProfile) {
            profilePref.setChecked(device.getMessageAccessPermission()
                    == BluetoothDevice.ACCESS_ALLOWED);
        } else if (profile instanceof PbapServerProfile) {
            profilePref.setChecked(device.getPhonebookAccessPermission()
                    == BluetoothDevice.ACCESS_ALLOWED);
        } else if (profile instanceof PanProfile) {
            profilePref.setChecked(profile.getConnectionStatus(device) ==
                    BluetoothProfile.STATE_CONNECTED);
        } else {
            profilePref.setChecked(profile.isEnabled(device));
        }

        if (profile instanceof A2dpProfile) {
            A2dpProfile a2dp = (A2dpProfile) profile;
            SwitchPreference highQualityPref = (SwitchPreference) mProfilesContainer.findPreference(
                    HIGH_QUALITY_AUDIO_PREF_TAG);
            if (highQualityPref != null) {
                if (a2dp.isEnabled(device) && a2dp.supportsHighQualityAudio(device)) {
                    highQualityPref.setVisible(true);
                    highQualityPref.setTitle(a2dp.getHighQualityAudioOptionLabel(device));
                    highQualityPref.setChecked(a2dp.isHighQualityAudioEnabled(device));
                    highQualityPref.setEnabled(!mCachedDevice.isBusy() && !isLeAudioEnabled);
                } else {
                    highQualityPref.setVisible(false);
                }
            }
        }
    }

    private boolean isLeAudioEnabled(){
        LocalBluetoothProfile leAudio = mProfileManager.getLeAudioProfile();
        if (leAudio != null) {
            List<CachedBluetoothDevice> leAudioDeviceList = mProfileDeviceMap.get(
                    leAudio.toString());
            if (leAudioDeviceList != null
                    && leAudioDeviceList.stream()
                    .anyMatch(item -> leAudio.isEnabled(item.getDevice()))) {
                return true;
            }
        }
        return false;
    }

    private void grayOutPreferenceWhenLeAudioIsEnabled(LocalBluetoothProfile profile) {
        if (profile != null) {
            SwitchPreference pref = mProfilesContainer.findPreference(profile.toString());
            if (pref != null) {
                Log.d(TAG, "LE is enabled, gray out " + profile.toString());
                pref.setEnabled(false);
            }
        }
    }

    /**
     * Helper method to enable a profile for a device.
     */
    private void enableProfile(LocalBluetoothProfile profile) {
        final BluetoothDevice bluetoothDevice = mCachedDevice.getDevice();
        if (profile instanceof PbapServerProfile) {
            bluetoothDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
            // We don't need to do the additional steps below for this profile.
            return;
        }
        if (profile instanceof MapProfile) {
            bluetoothDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
        }

        if (profile instanceof LeAudioProfile) {
            enableLeAudioProfile(profile);
            return;
        }

        profile.setEnabled(bluetoothDevice, true);
    }

    /**
     * Helper method to disable a profile for a device
     */
    private void disableProfile(LocalBluetoothProfile profile) {
        if (profile instanceof LeAudioProfile) {
            disableLeAudioProfile(profile);
            return;
        }

        final BluetoothDevice bluetoothDevice = mCachedDevice.getDevice();
        profile.setEnabled(bluetoothDevice, false);

        if (profile instanceof MapProfile) {
            bluetoothDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_REJECTED);
        } else if (profile instanceof PbapServerProfile) {
            bluetoothDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_REJECTED);
        }
    }

    /**
     * When the pref for a bluetooth profile is clicked on, we want to toggle the enabled/disabled
     * state for that profile.
     */
    @Override
    public boolean onPreferenceClick(Preference preference) {
        mProfile = mProfileManager.getProfileByName(preference.getKey());
        if (mProfile == null) {
            // It might be the PbapServerProfile, which is not stored by name.
            PbapServerProfile psp = mManager.getProfileManager().getPbapProfile();
            if (TextUtils.equals(preference.getKey(), psp.toString())) {
                mProfile = psp;
            } else {
                return false;
            }
        }
        mProfilePref = (SwitchPreference) preference;
        if (mIsGroupDevice && mIsProfileConfirmDialogSupported) {
            showProfileConfirmDialog();
        } else {
            enableOrDisableProfile();
        }
        return true;
    }

    /**
     * Helper to get the list of connectable and special profiles.
     */
    private List<LocalBluetoothProfile> getProfiles() {
        List<LocalBluetoothProfile> result = new ArrayList<LocalBluetoothProfile>();
        mProfileDeviceMap.clear();
        if (mAllOfCachedDevices == null || mAllOfCachedDevices.isEmpty()) {
            return result;
        }
        for (CachedBluetoothDevice cachedItem : mAllOfCachedDevices) {
            List<LocalBluetoothProfile> tmpResult = cachedItem.getConnectableProfiles();
            for (LocalBluetoothProfile profile : tmpResult) {
                if (mProfileDeviceMap.containsKey(profile.toString())) {
                    mProfileDeviceMap.get(profile.toString()).add(cachedItem);
                    Log.d(TAG, "getProfiles: " + profile.toString() + " add device "
                            + cachedItem.getDevice().getAnonymizedAddress());
                } else {
                    List<CachedBluetoothDevice> tmpCachedDeviceList =
                            new ArrayList<CachedBluetoothDevice>();
                    tmpCachedDeviceList.add(cachedItem);
                    mProfileDeviceMap.put(profile.toString(), tmpCachedDeviceList);
                    result.add(profile);
                }
            }
        }

        final BluetoothDevice device = mCachedDevice.getDevice();
        final int pbapPermission = device.getPhonebookAccessPermission();
        // Only provide PBAP cabability if the client device has requested PBAP.
        if (pbapPermission != BluetoothDevice.ACCESS_UNKNOWN) {
            final PbapServerProfile psp = mManager.getProfileManager().getPbapProfile();
            result.add(psp);
        }

        final MapProfile mapProfile = mManager.getProfileManager().getMapProfile();
        final int mapPermission = device.getMessageAccessPermission();
        if (mapPermission != BluetoothDevice.ACCESS_UNKNOWN) {
            result.add(mapProfile);
        }
        Log.d(TAG, "getProfiles:result:" + result);
        return result;
    }

    private List<CachedBluetoothDevice> getAllOfCachedBluetoothDevices() {
        List<CachedBluetoothDevice> cachedBluetoothDevices = new ArrayList<>();
        if (mCachedDevice == null) {
            return cachedBluetoothDevices;
        }
        cachedBluetoothDevices.add(mCachedDevice);
        if (mCachedDevice.getGroupId() != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
            for (CachedBluetoothDevice member : mCachedDevice.getMemberDevice()) {
                cachedBluetoothDevices.add(member);
            }
        }
        return cachedBluetoothDevices;
    }

    /**
     * When user disable the Le Audio profile, the system needs to do two things.
     * 1) Disable the Le Audio profile, VCP, CSIP and BASS for each of the Le Audio devices.
     * 2) Enable the A2dp profile and Headset profile for the associated device. The system
     * can't enable the A2dp profile and Headset profile if the Le Audio profile is enabled.
     *
     * @param profile the LeAudio profile
     */
    private void disableLeAudioProfile(LocalBluetoothProfile profile) {
        if (profile == null || mProfileDeviceMap.get(profile.toString()) == null) {
            Log.e(TAG, "There is no the LE profile or no device in mProfileDeviceMap. Do nothing.");
            return;
        }
        LocalBluetoothProfile vcp = mProfileManager.getVolumeControlProfile();
        LocalBluetoothProfile csip = mProfileManager.getCsipSetCoordinatorProfile();
        LocalBluetoothProfile bass = mProfileManager.getBCProfile();
        LocalBluetoothProfile a2dp = mProfileManager.getA2dpProfile();
        LocalBluetoothProfile headset = mProfileManager.getHeadsetProfile();

        for (CachedBluetoothDevice leAudioDevice : mProfileDeviceMap.get(profile.toString())) {
            Log.d(TAG,
                    "User disable LE device: " + leAudioDevice.getDevice().getAnonymizedAddress());
            profile.setEnabled(leAudioDevice.getDevice(), false);
            if (vcp != null) {
                vcp.setEnabled(leAudioDevice.getDevice(), false);
            }
            if (csip != null) {
                csip.setEnabled(leAudioDevice.getDevice(), false);
            }
            if (bass != null) {
                bass.setEnabled(leAudioDevice.getDevice(), false);
            }
        }

        enableProfileAfterUserDisablesLeAudio(a2dp);
        enableProfileAfterUserDisablesLeAudio(headset);
    }

    /**
     * When user enable the Le Audio profile, the system needs to do two things.
     * 1) Disable the A2dp profile and Headset profile for the associated device. The system
     * can't enable the Le Audio if the A2dp profile and Headset profile are enabled.
     * 2) Enable the Le Audio profile, VCP, CSIP and BASS for each of the Le Audio devices.
     *
     * @param profile the LeAudio profile
     */
    private void enableLeAudioProfile(LocalBluetoothProfile profile) {
        if (profile == null || mProfileDeviceMap.get(profile.toString()) == null) {
            Log.e(TAG, "There is no the LE profile or no device in mProfileDeviceMap. Do nothing.");
            return;
        }
        LocalBluetoothProfile a2dp = mProfileManager.getA2dpProfile();
        LocalBluetoothProfile headset = mProfileManager.getHeadsetProfile();
        LocalBluetoothProfile vcp = mProfileManager.getVolumeControlProfile();
        LocalBluetoothProfile csip = mProfileManager.getCsipSetCoordinatorProfile();
        LocalBluetoothProfile bass = mProfileManager.getBCProfile();

        disableProfileBeforeUserEnablesLeAudio(a2dp);
        disableProfileBeforeUserEnablesLeAudio(headset);

        for (CachedBluetoothDevice leAudioDevice : mProfileDeviceMap.get(profile.toString())) {
            Log.d(TAG,
                    "User enable LE device: " + leAudioDevice.getDevice().getAnonymizedAddress());
            profile.setEnabled(leAudioDevice.getDevice(), true);
            if (vcp != null) {
                vcp.setEnabled(leAudioDevice.getDevice(), true);
            }
            if (csip != null) {
                csip.setEnabled(leAudioDevice.getDevice(), true);
            }
            if (bass != null) {
                bass.setEnabled(leAudioDevice.getDevice(), true);
            }
        }
    }

    private void disableProfileBeforeUserEnablesLeAudio(LocalBluetoothProfile profile) {
        if (profile != null && mProfileDeviceMap.get(profile.toString()) != null) {
            Log.d(TAG, "Disable " + profile.toString() + " before user enables LE");
            for (CachedBluetoothDevice profileDevice : mProfileDeviceMap.get(profile.toString())) {
                if (profile.isEnabled(profileDevice.getDevice())) {
                    profile.setEnabled(profileDevice.getDevice(), false);
                } else {
                    Log.d(TAG, "The " + profile.toString() + " profile is disabled. Do nothing.");
                }
            }
        }
    }

    private void enableProfileAfterUserDisablesLeAudio(LocalBluetoothProfile profile) {
        if (profile != null && mProfileDeviceMap.get(profile.toString()) != null) {
            Log.d(TAG, "enable " + profile.toString() + "after user disables LE");
            for (CachedBluetoothDevice profileDevice : mProfileDeviceMap.get(profile.toString())) {
                if (!profile.isEnabled(profileDevice.getDevice())) {
                    profile.setEnabled(profileDevice.getDevice(), true);
                } else {
                    Log.d(TAG, "The " + profile.toString() + " profile is enabled. Do nothing.");
                }
            }
        }
    }

    /**
     * This is a helper method to be called after adding a Preference for a profile. If that
     * profile happened to be A2dp and the device supports high quality audio, it will add a
     * separate preference for controlling whether to actually use high quality audio.
     *
     * @param profile the profile just added
     */
    private void maybeAddHighQualityAudioPref(LocalBluetoothProfile profile) {
        if (!(profile instanceof A2dpProfile)) {
            return;
        }
        BluetoothDevice device = mCachedDevice.getDevice();
        A2dpProfile a2dp = (A2dpProfile) profile;
        if (a2dp.isProfileReady() && a2dp.supportsHighQualityAudio(device)) {
            SwitchPreference highQualityAudioPref = new SwitchPreference(
                    mProfilesContainer.getContext());
            highQualityAudioPref.setKey(HIGH_QUALITY_AUDIO_PREF_TAG);
            highQualityAudioPref.setVisible(false);
            highQualityAudioPref.setOnPreferenceClickListener(clickedPref -> {
                highQualityAudioPref.setEnabled(false);
                boolean enable = ((SwitchPreference) clickedPref).isChecked();
                if ((a2dp.isMandatoryCodec(device) && !enable) ||
                     (!a2dp.isMandatoryCodec(device) && enable))
                     highQualityAudioPref.setEnabled(true);
                a2dp.setHighQualityAudioEnabled(mCachedDevice.getDevice(), enable);
                return true;
            });
            mProfilesContainer.addPreference(highQualityAudioPref);
        }
    }

    @Override
    public void onPause() {
        for (CachedBluetoothDevice item : mAllOfCachedDevices) {
            item.unregisterCallback(this);
        }
        mProfileManager.removeServiceListener(this);
        mManager.getEventManager().unregisterCallback(this);
    }

    @Override
    public void onResume() {
        for (CachedBluetoothDevice item : mAllOfCachedDevices) {
            item.registerCallback(this);
        }
        mProfileManager.addServiceListener(this);
        mManager.getEventManager().registerCallback(this);
    }

    @Override
    public void onDeviceAttributesChanged() {
        for (CachedBluetoothDevice item : mAllOfCachedDevices) {
            item.unregisterCallback(this);
        }
        mAllOfCachedDevices = getAllOfCachedBluetoothDevices();
        for (CachedBluetoothDevice item : mAllOfCachedDevices) {
            item.registerCallback(this);
        }

        super.onDeviceAttributesChanged();
    }

    @Override
    public void onServiceConnected() {
        refresh();
    }

    @Override
    public void onServiceDisconnected() {
        refresh();
    }

    private void updateA2dpHighQualityAudioPref() {
        A2dpProfile a2dp = null;
        for (LocalBluetoothProfile profile : getProfiles()) {
            if (profile instanceof A2dpProfile) {
                if (profile.isProfileReady()) {
                    a2dp = (A2dpProfile)profile;
                }
                break;
            }
        }
        if (a2dp == null) {
            return;
        }

        BluetoothDevice device = mCachedDevice.getDevice();
        SwitchPreference highQualityPref = (SwitchPreference) mProfilesContainer.findPreference(
                HIGH_QUALITY_AUDIO_PREF_TAG);
        if (highQualityPref != null) {
            if (a2dp.isEnabled(device) && a2dp.supportsHighQualityAudio(device)) {
                highQualityPref.setTitle(a2dp.getHighQualityAudioOptionLabel(device));
                highQualityPref.setChecked(a2dp.isHighQualityAudioEnabled(device));
                highQualityPref.setEnabled(true);
            }
        }
    }

    @Override
    public void onA2dpCodecConfigChanged(CachedBluetoothDevice cachedDevice,
            BluetoothCodecStatus codecStatus) {
        if (!cachedDevice.equals(mCachedDevice)) {
            return;
        }
        updateA2dpHighQualityAudioPref();
    }

    /**
     * Refreshes the state of the switches for all profiles, possibly adding or removing switches as
     * needed.
     */
    @Override
    protected void refresh() {
        for (LocalBluetoothProfile profile : getProfiles()) {
            if (profile == null || !profile.isProfileReady()) {
                continue;
            }
            SwitchPreference pref = mProfilesContainer.findPreference(
                    profile.toString());
            if (pref == null) {
                pref = createProfilePreference(mProfilesContainer.getContext(), profile);
                mProfilesContainer.addPreference(pref);
                maybeAddHighQualityAudioPref(profile);
            }
            refreshProfilePreference(pref, profile);
        }
        for (LocalBluetoothProfile removedProfile : mCachedDevice.getRemovedProfiles()) {
            final SwitchPreference pref = mProfilesContainer.findPreference(
                    removedProfile.toString());
            if (pref != null) {
                mProfilesContainer.removePreference(pref);
            }
        }

        Preference preference = mProfilesContainer.findPreference(KEY_BOTTOM_PREFERENCE);
        if (preference == null) {
            preference = new Preference(mContext);
            preference.setLayoutResource(R.layout.preference_bluetooth_profile_category);
            preference.setEnabled(false);
            preference.setKey(KEY_BOTTOM_PREFERENCE);
            preference.setOrder(ORDINAL);
            preference.setSelectable(false);
            mProfilesContainer.addPreference(preference);
        }
    }

    @Override
    public String getPreferenceKey() {
        return KEY_PROFILES_GROUP;
    }

    private void initGroupBluetoothProfileConfirm() {
        if (mIsGroupDevice) {
            if (mGroupBluetoothProfileConfirm != null) {
                mGroupBluetoothProfileConfirm.dismiss();
                mGroupBluetoothProfileConfirm = null;
            }
            mGroupBluetoothProfileConfirm =
                    GroupBluetoothProfileSwitchConfirmDialog.newInstance(mGroupId);
            mGroupBluetoothProfileConfirm.setPairingController(this);
        }
    }

    @Override
    public void onDialogNegativeClick() {
        resetProfileSwitch();
        mGroupBluetoothProfileConfirm.dismiss();
    }

    @Override
    public void onDialogPositiveClick() {
        enableOrDisableProfile();
        mGroupBluetoothProfileConfirm.dismiss();
    }

    private void showProfileConfirmDialog() {
        initGroupBluetoothProfileConfirm();
        mGroupBluetoothProfileConfirm.show(mFragment.getFragmentManager(),
                GroupBluetoothProfileSwitchConfirmDialog.TAG);
    }

    private void resetProfileSwitch() {
        mProfilePref.setChecked(!mProfilePref.isChecked());
    }

    private void enableOrDisableProfile() {
        if (mProfilePref.isChecked()) {
            enableProfile(mProfile);
        } else {
            disableProfile(mProfile);
        }
        refreshProfilePreference(mProfilePref, mProfile);
    }
}

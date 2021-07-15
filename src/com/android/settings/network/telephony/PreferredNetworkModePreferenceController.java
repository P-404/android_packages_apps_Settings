/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.network.telephony;

import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.network.telephony.TelephonyConstants.TelephonyManagerConstants;

/**
 * Preference controller for "Preferred network mode"
 */
public class PreferredNetworkModePreferenceController extends TelephonyBasePreferenceController
        implements ListPreference.OnPreferenceChangeListener, LifecycleObserver {

    private static final String LOG_TAG = "PreferredNetworkMode";
    private CarrierConfigManager mCarrierConfigManager;
    private ContentObserver mPreferredNetworkModeObserver;
    private ContentObserver mSubsidySettingsObserver;
    private TelephonyManager mTelephonyManager;
    private PersistableBundle mPersistableBundle;
    private boolean mIsGlobalCdma;
    private Preference mPreference;
    private PhoneCallStateListener mPhoneStateListener;
    @VisibleForTesting
    Integer mCallState;

    // Local cache for Primary Card and Subsidy Lock related vendor properties. Reading these
    // properties are a costly affair since they involve two IPC calls, an AIDL and another HIDL.
    // So we cache these and reuse them as and when applicable.
    boolean mIsPrimaryCardEnabled = false;
    boolean mIsPrimaryCardLWEnabled = false;
    boolean mIsSubsidyLockFeatureEnabled = false;

    public PreferredNetworkModePreferenceController(Context context, String key) {
        super(context, key);
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        mSubsidySettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (mPreference != null) {
                    if (PrimaryCardAndSubsidyLockUtils.DBG) {
                        Log.d(LOG_TAG, "mSubsidySettingsObserver#onChange");
                    }
                    updateState(mPreference);
                }
            }
        };
        mPreferredNetworkModeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (mPreference != null) {
                    Log.d(LOG_TAG, "mPreferredNetworkModeObserver#onChange");
                    updateState(mPreference);
                }
            }
        };
    }

    @Override
    public int getAvailabilityStatus(int subId) {
        final PersistableBundle carrierConfig = mCarrierConfigManager.getConfigForSubId(subId);
        boolean visible;
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            visible = false;
        } else if (carrierConfig == null) {
            visible = false;
        } else if (carrierConfig.getBoolean(
                CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)
                || carrierConfig.getBoolean(
                CarrierConfigManager.KEY_HIDE_PREFERRED_NETWORK_TYPE_BOOL)) {
            visible = false;
        } else if (carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_PHONE_BOOL)) {
            visible = true;
        } else {
            visible = false;
        }

        return visible ? AVAILABLE : CONDITIONALLY_UNAVAILABLE;
    }

    @OnLifecycleEvent(ON_START)
    public void onStart() {
        if (mPhoneStateListener != null) {
            mPhoneStateListener.register(mContext, mSubId);
        }

        loadPrimaryCardAndSubsidyLockValues();
        if (mIsSubsidyLockFeatureEnabled) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(PrimaryCardAndSubsidyLockUtils.SUBSIDY_STATUS), false,
                    mSubsidySettingsObserver);
        }
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.PREFERRED_NETWORK_MODE + mSubId), true,
                mPreferredNetworkModeObserver);
    }

    @OnLifecycleEvent(ON_STOP)
    public void onStop() {
        if (mPhoneStateListener != null) {
            mPhoneStateListener.unregister();
        }
        if (mSubsidySettingsObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mSubsidySettingsObserver);
        }
        if (mPreferredNetworkModeObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mPreferredNetworkModeObserver);
        }
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        final ListPreference listPreference = (ListPreference) preference;
        final int networkMode = getPreferredNetworkMode();
        updatePreferenceEntries(listPreference);
        listPreference.setValue(Integer.toString(networkMode));
        listPreference.setSummary(getPreferredNetworkModeSummaryResId(networkMode));
        listPreference.setEnabled(isCallStateIdle());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object object) {
        final int newPreferredNetworkMode = Integer.parseInt((String) object);

        if (mTelephonyManager.setPreferredNetworkTypeBitmask(
                MobileNetworkUtils.getRafFromNetworkType(newPreferredNetworkMode))) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE + mSubId,
                    newPreferredNetworkMode);
            final ListPreference listPreference = (ListPreference) preference;
            listPreference.setSummary(getPreferredNetworkModeSummaryResId(newPreferredNetworkMode));
            return true;
        }

        return false;
    }

    public void init(Lifecycle lifecycle, int subId) {
        mSubId = subId;
        if (mPhoneStateListener == null) {
            mPhoneStateListener = new PhoneCallStateListener();
        }
        final PersistableBundle carrierConfig = mCarrierConfigManager.getConfigForSubId(mSubId);
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId);

        mIsGlobalCdma = mTelephonyManager.isLteCdmaEvdoGsmWcdmaEnabled()
                && carrierConfig.getBoolean(CarrierConfigManager.KEY_SHOW_CDMA_CHOICES_BOOL);

        lifecycle.addObserver(this);
    }

    private int getPreferredNetworkMode() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.PREFERRED_NETWORK_MODE + mSubId,
                TelephonyManager.DEFAULT_PREFERRED_NETWORK_MODE);
    }

    private int getPreferredNetworkModeSummaryResId(int NetworkMode) {
        switch (NetworkMode) {
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA:
                return R.string.preferred_network_mode_tdscdma_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_GSM:
                return R.string.preferred_network_mode_tdscdma_gsm_summary;
            case TelephonyManagerConstants.NETWORK_MODE_WCDMA_PREF:
                return R.string.preferred_network_mode_wcdma_perf_summary;
            case TelephonyManagerConstants.NETWORK_MODE_GSM_ONLY:
                return R.string.preferred_network_mode_gsm_only_summary;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_WCDMA:
                return R.string.preferred_network_mode_tdscdma_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_WCDMA_ONLY:
                return R.string.preferred_network_mode_wcdma_only_summary;
            case TelephonyManagerConstants.NETWORK_MODE_GSM_UMTS:
                return R.string.preferred_network_mode_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_CDMA_EVDO:
                return mTelephonyManager.isLteCdmaEvdoGsmWcdmaEnabled()
                        ? R.string.preferred_network_mode_cdma_summary
                        : R.string.preferred_network_mode_cdma_evdo_summary;
            case TelephonyManagerConstants.NETWORK_MODE_CDMA_NO_EVDO:
                return R.string.preferred_network_mode_cdma_only_summary;
            case TelephonyManagerConstants.NETWORK_MODE_EVDO_NO_CDMA:
                return R.string.preferred_network_mode_evdo_only_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA:
                return R.string.preferred_network_mode_lte_tdscdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_ONLY:
                return R.string.preferred_network_mode_lte_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_GSM:
                return R.string.preferred_network_mode_lte_tdscdma_gsm_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
                return R.string.preferred_network_mode_lte_tdscdma_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_GSM_WCDMA:
                return R.string.preferred_network_mode_lte_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_CDMA_EVDO:
                return R.string.preferred_network_mode_lte_cdma_evdo_summary;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_ONLY:
                return R.string.preferred_network_mode_tdscdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return R.string.preferred_network_mode_lte_tdscdma_cdma_evdo_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA
                        || mIsGlobalCdma
                        || MobileNetworkUtils.isWorldMode(mContext, mSubId)) {
                    return R.string.preferred_network_mode_lte_cdma_evdo_gsm_wcdma_summary;
                } else {
                    return R.string.preferred_network_mode_lte_summary;
                }
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return R.string.preferred_network_mode_tdscdma_cdma_evdo_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_GLOBAL:
                return R.string.preferred_network_mode_cdma_evdo_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA:
                return R.string.preferred_network_mode_lte_tdscdma_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_WCDMA:
                return R.string.preferred_network_mode_lte_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_ONLY:
                return R.string.preferred_network_mode_nr_only_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE:
                return R.string.preferred_network_mode_nr_lte_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO:
                return R.string.preferred_network_mode_nr_lte_cdma_evdo_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_GSM_WCDMA:
                return R.string.preferred_network_mode_nr_lte_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA:
                return R.string.preferred_network_mode_global_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_WCDMA:
                return R.string.preferred_network_mode_nr_lte_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA:
                return R.string.preferred_network_mode_nr_lte_tdscdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM:
                return R.string.preferred_network_mode_nr_lte_tdscdma_gsm_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA:
                return R.string.preferred_network_mode_nr_lte_tdscdma_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA:
                return R.string.preferred_network_mode_nr_lte_tdscdma_gsm_wcdma_summary;
            case TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return R.string.preferred_network_mode_nr_lte_tdscdma_cdma_evdo_gsm_wcdma_summary;
            default:
                return R.string.preferred_network_mode_global_summary;
        }
    }

    private void updatePreferenceEntries(ListPreference preference) {
        // Default values
        preference.setEntries(R.array.preferred_network_mode_choices);
        preference.setEntryValues(R.array.preferred_network_mode_values);

        // Primary Card Feature
        // If the current SIM is not the primary card
        //     1. If PrimaryCardL_W is enabled, restrict mode selection to GSM and WCDMA options.
        //     2. If the current mode is GSM_only, disable the network mode preference on the UI.
        final int currentPrimarySlot = Settings.Global.getInt(mContext.getContentResolver(),
                PrimaryCardAndSubsidyLockUtils.CONFIG_CURRENT_PRIMARY_SUB,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);

        boolean isCurrentPrimarySlotValid = currentPrimarySlot >= 0
                && currentPrimarySlot < mTelephonyManager.getActiveModemCount();

        int currentPhoneId = SubscriptionManager.getPhoneId(mSubId);

        Log.d(LOG_TAG, "currentPrimarySlot: " + currentPrimarySlot
                + ", isCurrentPrimarySlotValid: " + isCurrentPrimarySlotValid
                + ", currentPhoneId: " + currentPhoneId);

        if (mIsPrimaryCardEnabled) {
            if (PrimaryCardAndSubsidyLockUtils.DBG) {
                Log.d(LOG_TAG, "isPrimaryCardEnabled: true");
            }
            if (isCurrentPrimarySlotValid
                    && currentPhoneId != currentPrimarySlot) {
                if (mIsPrimaryCardLWEnabled) {
                    Log.d(LOG_TAG, "Primary card LW is enabled");
                    preference.setEntries(R.array.preferred_network_mode_gsm_wcdma_choices);
                    preference.setEntryValues(R.array.preferred_network_mode_gsm_wcdma_values);
                } else if (getPreferredNetworkMode() == TelephonyManager.NETWORK_MODE_GSM_ONLY) {
                    Log.d(LOG_TAG, "Network mode is GSM only, disabling the preference");
                    preference.setEnabled(false);
                }
            }
        }

        // Subsidy Lock Feature
        // If subsidy is unlocked,
        //     1. Change the entries in the network mode choices for the primary sub.
        //     2. Disable the network mode preference on the UI for the non-primary sub.
        if (PrimaryCardAndSubsidyLockUtils.DBG) {
            Log.d(LOG_TAG, "isSubsidyLockFeatureEnabled: " + mIsSubsidyLockFeatureEnabled);
            Log.d(LOG_TAG, "isSubsidyUnlocked: "
                    + PrimaryCardAndSubsidyLockUtils.isSubsidyUnlocked(mContext));
        }

        if (mIsSubsidyLockFeatureEnabled
                && PrimaryCardAndSubsidyLockUtils.isSubsidyUnlocked(mContext)) {
            if (PrimaryCardAndSubsidyLockUtils.DBG) {
                Log.d(LOG_TAG, "Subsidy is unlocked");
            }
            if (isCurrentPrimarySlotValid) {
                if (currentPhoneId == currentPrimarySlot) {
                    Log.d(LOG_TAG, "Primary sub, change to subsidy choices");
                    preference.setEntries(R.array.enabled_networks_subsidy_locked_choices);
                    preference.setEntryValues(R.array.enabled_networks_subsidy_locked_values);
                } else {
                    Log.d(LOG_TAG, "Non-primary sub, disable the preference");
                    preference.setEnabled(false);
                }
            }
        }
    }

    private void loadPrimaryCardAndSubsidyLockValues() {
        Log.d(LOG_TAG, "loadPrimaryCardAndSubsidyLockValues");
        mIsPrimaryCardEnabled = PrimaryCardAndSubsidyLockUtils.isPrimaryCardEnabled();
        mIsPrimaryCardLWEnabled = PrimaryCardAndSubsidyLockUtils.isPrimaryCardLWEnabled();
        mIsSubsidyLockFeatureEnabled = PrimaryCardAndSubsidyLockUtils.isSubsidyLockFeatureEnabled();

        if (PrimaryCardAndSubsidyLockUtils.DBG) {
            Log.d(LOG_TAG, "mIsPrimaryCardEnabled: " + mIsPrimaryCardEnabled);
            Log.d(LOG_TAG, "mIsPrimaryCardLWEnabled: " + mIsPrimaryCardLWEnabled);
            Log.d(LOG_TAG, "mIsSubsidyLockFeatureEnabled: " + mIsSubsidyLockFeatureEnabled);
        }
    }

    private boolean isCallStateIdle() {
        boolean callStateIdle = true;
        if (mCallState != null && mCallState != TelephonyManager.CALL_STATE_IDLE) {
            callStateIdle = false;
        }
        Log.d(LOG_TAG, "isCallStateIdle:" + callStateIdle);
        return callStateIdle;
    }

    private class PhoneCallStateListener extends PhoneStateListener {

        PhoneCallStateListener() {
            super(Looper.getMainLooper());
        }

        private TelephonyManager mTelephonyManager;

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            mCallState = state;
            updateState(mPreference);
        }

        public void register(Context context, int subId) {
            mTelephonyManager = context.getSystemService(TelephonyManager.class);
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                mTelephonyManager = mTelephonyManager.createForSubscriptionId(subId);
            }
            mTelephonyManager.listen(this, PhoneStateListener.LISTEN_CALL_STATE);

        }

        public void unregister() {
            mCallState = null;
            mTelephonyManager.listen(this, PhoneStateListener.LISTEN_NONE);
        }
    }
}

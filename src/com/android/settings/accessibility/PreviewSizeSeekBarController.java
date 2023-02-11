/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settings.accessibility;

import android.content.Context;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceScreen;

import com.android.settings.core.BasePreferenceController;
import com.android.settings.widget.LabeledSeekBarPreference;

import java.util.Optional;

/**
 * The controller of {@link LabeledSeekBarPreference} that listens to display size and font size
 * settings changes and updates preview size threshold smoothly.
 */
class PreviewSizeSeekBarController extends BasePreferenceController implements
        TextReadingResetController.ResetStateListener {
    private final PreviewSizeData<? extends Number> mSizeData;
    private boolean mSeekByTouch;
    private Optional<ProgressInteractionListener> mInteractionListener = Optional.empty();
    private LabeledSeekBarPreference mSeekBarPreference;

    private final SeekBar.OnSeekBarChangeListener mSeekBarChangeListener =
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (mInteractionListener.isEmpty()) {
                        return;
                    }

                    final ProgressInteractionListener interactionListener =
                            mInteractionListener.get();
                    // Avoid timing issues to update the corresponding preview fail when clicking
                    // the increase/decrease button.
                    seekBar.post(interactionListener::notifyPreferenceChanged);

                    if (!mSeekByTouch) {
                        interactionListener.onProgressChanged();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mSeekByTouch = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mSeekByTouch = false;

                    mInteractionListener.ifPresent(ProgressInteractionListener::onEndTrackingTouch);
                }
            };

    PreviewSizeSeekBarController(Context context, String preferenceKey,
            @NonNull PreviewSizeData<? extends Number> sizeData) {
        super(context, preferenceKey);
        mSizeData = sizeData;
    }

    void setInteractionListener(ProgressInteractionListener interactionListener) {
        mInteractionListener = Optional.ofNullable(interactionListener);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);

        final int dataSize = mSizeData.getValues().size();
        final int initialIndex = mSizeData.getInitialIndex();
        mSeekBarPreference = screen.findPreference(getPreferenceKey());
        mSeekBarPreference.setMax(dataSize - 1);
        mSeekBarPreference.setProgress(initialIndex);
        mSeekBarPreference.setContinuousUpdates(true);
        mSeekBarPreference.setOnSeekBarChangeListener(mSeekBarChangeListener);
    }

    @Override
    public void resetState() {
        final int defaultProgress = mSizeData.getValues().indexOf(mSizeData.getDefaultValue());
        mSeekBarPreference.setProgress(defaultProgress);

        // Immediately take the effect of updating the progress to avoid waiting for receiving
        // the event to delay update.
        mInteractionListener.ifPresent(ProgressInteractionListener::onProgressChanged);
    }


    /**
     * Interface for callbacks when users interact with the seek bar.
     */
    interface ProgressInteractionListener {

        /**
         * Called when the progress is changed.
         */
        void notifyPreferenceChanged();

        /**
         * Called when the progress is changed without tracking touch.
         */
        void onProgressChanged();

        /**
         * Called when the seek bar is end tracking.
         */
        void onEndTrackingTouch();
    }
}

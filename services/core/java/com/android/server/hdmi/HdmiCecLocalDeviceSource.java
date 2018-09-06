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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.Constants.LocalActivePort;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import java.util.List;

/**
 * Represent a logical source device residing in Android system.
 */
abstract class HdmiCecLocalDeviceSource extends HdmiCecLocalDevice {

    private static final String TAG = "HdmiCecLocalDeviceSource";

    // Indicate if current device is Active Source or not
    private boolean mIsActiveSource = false;

    // Device has cec switch functionality or not.
    // Default is false.
    protected boolean mIsSwitchDevice = SystemProperties.getBoolean(
            Constants.PROPERTY_HDMI_IS_DEVICE_HDMI_CEC_SWITCH, false);

    // Local active port number used for Routing Control.
    // This records the default active port or the previous valid active port.
    // Default is HOME input.
    // Note that we don't save active path here because for source device,
    // new Active Source physical address might not match the local active path
    @GuardedBy("mLock")
    @LocalActivePort
    private int mLocalActivePort = Constants.CEC_SWITCH_HOME;

    protected HdmiCecLocalDeviceSource(HdmiControlService service, int deviceType) {
        super(service, deviceType);
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        mCecMessageCache.flushAll();
        // We'll not clear mIsActiveSource on the hotplug event to pass CETC 11.2.2-2 ~ 3.
        if (mService.isPowerStandbyOrTransient()) {
            mService.wakeUp();
        }
    }

    @Override
    @ServiceThreadOnly
    protected void sendStandby(int deviceId) {
        assertRunOnServiceThread();

        // Send standby to TV only for now
        int targetAddress = Constants.ADDR_TV;
        mService.sendCecCommand(HdmiCecMessageBuilder.buildStandby(mAddress, targetAddress));
    }

    @ServiceThreadOnly
    void oneTouchPlay(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<OneTouchPlayAction> actions = getActions(OneTouchPlayAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "oneTouchPlay already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        OneTouchPlayAction action = OneTouchPlayAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate oneTouchPlay");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    private void invokeCallback(IHdmiControlCallback callback, int result) {
        assertRunOnServiceThread();
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
        if (!mActiveSource.equals(activeSource)) {
            setActiveSource(activeSource);
        }
        setIsActiveSource(physicalAddress == mService.getPhysicalAddress());
        switchInputOnReceivingNewActivePath(physicalAddress);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        maySendActiveSource(message.getSource());
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        // If current device is the target path, set to Active Source.
        // If the path is under the current device, should switch
        if (physicalAddress == mService.getPhysicalAddress() && mService.isPlaybackDevice()) {
            setAndBroadcastActiveSource(message, physicalAddress);
        }
        switchInputOnReceivingNewActivePath(physicalAddress);
        return true;
    }

    // Method to switch Input with the new Active Path.
    // All the devices with Switch functionality should implement this.
    protected void switchInputOnReceivingNewActivePath(int physicalAddress) {
        // do nothing
    }

    // Active source claiming needs to be handled in the parent class
    // since we decide who will be the active source when the device supports
    // multiple device types in this method.
    // This method should only be called when the device can be the active source.
    protected void setAndBroadcastActiveSource(HdmiCecMessage message, int physicalAddress) {
        // If the device has both playback and audio system logical addresses,
        // playback will claim active source. Otherwise audio system will.
        HdmiCecLocalDevice deviceToBeActiveSource = mService.playback();
        if (deviceToBeActiveSource == null) {
            deviceToBeActiveSource = mService.audioSystem();
        }
        if (this == deviceToBeActiveSource) {
            ActiveSource activeSource = ActiveSource.of(mAddress, physicalAddress);
            setIsActiveSource(true);
            setActiveSource(activeSource);
            wakeUpIfActiveSource();
            maySendActiveSource(message.getSource());
        }
    }

    @ServiceThreadOnly
    void setIsActiveSource(boolean on) {
        assertRunOnServiceThread();
        mIsActiveSource = on;
    }

    @ServiceThreadOnly
    // Check if current device is the Active Source
    boolean isActiveSource() {
        assertRunOnServiceThread();
        return mIsActiveSource;
    }

    protected void wakeUpIfActiveSource() {
        if (!mIsActiveSource) {
            return;
        }
        // Wake up the device if the power is in standby mode
        if (mService.isPowerStandbyOrTransient()) {
            mService.wakeUp();
        }
        return;
    }

    protected void maySendActiveSource(int dest) {
        if (mIsActiveSource) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildActiveSource(
                    mAddress, mService.getPhysicalAddress()));
        }
    }

    @VisibleForTesting
    protected void setLocalActivePort(@LocalActivePort int portId) {
        synchronized (mLock) {
            mLocalActivePort = portId;
        }
    }

    // To get the local active port to switch to
    // when receivng routing change or information.
    @LocalActivePort
    protected int getLocalActivePort() {
        synchronized (mLock) {
            return mLocalActivePort;
        }
    }
}

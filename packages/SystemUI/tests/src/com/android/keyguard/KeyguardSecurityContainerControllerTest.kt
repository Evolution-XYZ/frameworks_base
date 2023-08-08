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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.keyguard

import android.content.res.Configuration
import android.hardware.biometrics.BiometricOverlayConstants
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.testing.TestableResources
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardSecurityContainer.UserSwitcherViewMode.UserSwitcherCallback
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FaceAuthAccessibilityDelegate
import com.android.systemui.biometrics.SideFpsController
import com.android.systemui.biometrics.SideFpsUiRequestSource
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants
import com.android.systemui.classifier.FalsingA11yDelegate
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.log.SessionTracker
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.GlobalSettings
import com.google.common.truth.Truth
import java.util.Optional
import junit.framework.Assert
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class KeyguardSecurityContainerControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: KeyguardSecurityContainer
    @Mock
    private lateinit var adminSecondaryLockScreenControllerFactory:
        AdminSecondaryLockScreenController.Factory
    @Mock
    private lateinit var adminSecondaryLockScreenController: AdminSecondaryLockScreenController
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var inputViewController: KeyguardInputViewController<KeyguardInputView>
    @Mock private lateinit var windowInsetsController: WindowInsetsController
    @Mock private lateinit var securityViewFlipper: KeyguardSecurityViewFlipper
    @Mock private lateinit var viewFlipperController: KeyguardSecurityViewFlipperController
    @Mock private lateinit var messageAreaControllerFactory: KeyguardMessageAreaController.Factory
    @Mock private lateinit var keyguardMessageAreaController: KeyguardMessageAreaController<*>
    @Mock private lateinit var keyguardMessageArea: BouncerKeyguardMessageArea
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var emergencyButtonController: EmergencyButtonController
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var globalSettings: GlobalSettings
    @Mock private lateinit var userSwitcherController: UserSwitcherController
    @Mock private lateinit var sessionTracker: SessionTracker
    @Mock private lateinit var keyguardViewController: KeyguardViewController
    @Mock private lateinit var sideFpsController: SideFpsController
    @Mock private lateinit var keyguardPasswordViewControllerMock: KeyguardPasswordViewController
    @Mock private lateinit var falsingA11yDelegate: FalsingA11yDelegate
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var viewMediatorCallback: ViewMediatorCallback
    @Mock private lateinit var audioManager: AudioManager
    @Mock private lateinit var userInteractor: UserInteractor
    @Mock private lateinit var faceAuthAccessibilityDelegate: FaceAuthAccessibilityDelegate

    @Captor
    private lateinit var swipeListenerArgumentCaptor:
        ArgumentCaptor<KeyguardSecurityContainer.SwipeListener>
    @Captor
    private lateinit var onViewInflatedCallbackArgumentCaptor:
        ArgumentCaptor<KeyguardSecurityViewFlipperController.OnViewInflatedCallback>

    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var keyguardPasswordViewController: KeyguardPasswordViewController
    private lateinit var keyguardPasswordView: KeyguardPasswordView
    private lateinit var testableResources: TestableResources
    private lateinit var sceneTestUtils: SceneTestUtils
    private lateinit var sceneInteractor: SceneInteractor
    private lateinit var sceneTransitionStateFlow: MutableStateFlow<ObservableTransitionState>

    private lateinit var underTest: KeyguardSecurityContainerController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testableResources = mContext.getOrCreateTestableResources()
        testableResources.resources.configuration.orientation = Configuration.ORIENTATION_UNDEFINED
        whenever(view.context).thenReturn(mContext)
        whenever(view.resources).thenReturn(testableResources.resources)

        val lp = FrameLayout.LayoutParams(/* width=  */ 0, /* height= */ 0)
        lp.gravity = 0
        whenever(view.layoutParams).thenReturn(lp)

        whenever(adminSecondaryLockScreenControllerFactory.create(any()))
            .thenReturn(adminSecondaryLockScreenController)
        whenever(securityViewFlipper.windowInsetsController).thenReturn(windowInsetsController)
        keyguardPasswordView =
            spy(
                LayoutInflater.from(mContext).inflate(R.layout.keyguard_password_view, null)
                    as KeyguardPasswordView
            )
        whenever(keyguardPasswordView.rootView).thenReturn(securityViewFlipper)
        whenever<Any?>(keyguardPasswordView.requireViewById(R.id.bouncer_message_area))
            .thenReturn(keyguardMessageArea)
        whenever(messageAreaControllerFactory.create(any()))
            .thenReturn(keyguardMessageAreaController)
        whenever(keyguardPasswordView.windowInsetsController).thenReturn(windowInsetsController)
        whenever(keyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(SecurityMode.PIN)
        whenever(keyguardStateController.canDismissLockScreen()).thenReturn(true)

        featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.REVAMPED_BOUNCER_MESSAGES, true)
        featureFlags.set(Flags.SCENE_CONTAINER, false)
        featureFlags.set(Flags.BOUNCER_USER_SWITCHER, false)

        keyguardPasswordViewController =
            KeyguardPasswordViewController(
                keyguardPasswordView,
                keyguardUpdateMonitor,
                SecurityMode.Password,
                lockPatternUtils,
                null,
                messageAreaControllerFactory,
                null,
                null,
                emergencyButtonController,
                null,
                mock(),
                null,
                keyguardViewController,
                featureFlags
            )

        whenever(userInteractor.getSelectedUserId()).thenReturn(TARGET_USER_ID)
        sceneTestUtils = SceneTestUtils(this)
        sceneInteractor = sceneTestUtils.sceneInteractor()
        sceneTransitionStateFlow =
            MutableStateFlow(ObservableTransitionState.Idle(SceneKey.Lockscreen))
        sceneInteractor.setTransitionState(sceneTransitionStateFlow)

        underTest =
            KeyguardSecurityContainerController(
                view,
                adminSecondaryLockScreenControllerFactory,
                lockPatternUtils,
                keyguardUpdateMonitor,
                keyguardSecurityModel,
                metricsLogger,
                uiEventLogger,
                keyguardStateController,
                viewFlipperController,
                configurationController,
                falsingCollector,
                falsingManager,
                userSwitcherController,
                featureFlags,
                globalSettings,
                sessionTracker,
                Optional.of(sideFpsController),
                falsingA11yDelegate,
                telephonyManager,
                viewMediatorCallback,
                audioManager,
                mock(),
                mock(),
                { JavaAdapter(sceneTestUtils.testScope.backgroundScope) },
                userInteractor,
                faceAuthAccessibilityDelegate,
            ) {
                sceneInteractor
            }
    }

    @Test
    fun onInitConfiguresViewMode() {
        underTest.onInit()
        verify(view)
            .initMode(
                eq(KeyguardSecurityContainer.MODE_DEFAULT),
                eq(globalSettings),
                eq(falsingManager),
                eq(userSwitcherController),
                any(),
                eq(falsingA11yDelegate)
            )
    }

    @Test
    fun setAccessibilityDelegate() {
        verify(view).accessibilityDelegate = eq(faceAuthAccessibilityDelegate)
    }

    @Test
    fun showSecurityScreen_canInflateAllModes() {
        val modes = SecurityMode.values()
        for (mode in modes) {
            whenever(inputViewController.securityMode).thenReturn(mode)
            underTest.showSecurityScreen(mode)
            if (mode == SecurityMode.Invalid) {
                verify(viewFlipperController, never()).getSecurityView(any(), any(), any())
            } else {
                verify(viewFlipperController).getSecurityView(eq(mode), any(), any())
            }
        }
    }

    @Test
    fun onResourcesUpdate_callsThroughOnRotationChange() {
        clearInvocations(view)

        // Rotation is the same, shouldn't cause an update
        underTest.updateResources()
        verify(view, never())
            .initMode(
                eq(KeyguardSecurityContainer.MODE_DEFAULT),
                eq(globalSettings),
                eq(falsingManager),
                eq(userSwitcherController),
                any(),
                eq(falsingA11yDelegate)
            )

        // Update rotation. Should trigger update
        testableResources.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        underTest.updateResources()
        verify(view)
            .initMode(
                eq(KeyguardSecurityContainer.MODE_DEFAULT),
                eq(globalSettings),
                eq(falsingManager),
                eq(userSwitcherController),
                any(),
                eq(falsingA11yDelegate)
            )
    }

    private fun touchDown() {
        underTest.mGlobalTouchListener.onTouchEvent(
            MotionEvent.obtain(
                /* downTime= */ 0,
                /* eventTime= */ 0,
                MotionEvent.ACTION_DOWN,
                /* x= */ 0f,
                /* y= */ 0f,
                /* metaState= */ 0
            )
        )
    }

    @Test
    fun onInterceptTap_inhibitsFalsingInSidedSecurityMode() {
        whenever(view.isTouchOnTheOtherSideOfSecurity(any())).thenReturn(false)
        touchDown()
        verify(falsingCollector, never()).avoidGesture()
        whenever(view.isTouchOnTheOtherSideOfSecurity(any())).thenReturn(true)
        touchDown()
        verify(falsingCollector).avoidGesture()
    }

    @Test
    fun showSecurityScreen_oneHandedMode_flagDisabled_noOneHandedMode() {
        testableResources.addOverride(R.bool.can_use_one_handed_bouncer, false)
        setupGetSecurityView(SecurityMode.Pattern)
        underTest.showSecurityScreen(SecurityMode.Pattern)
        verify(view)
            .initMode(
                eq(KeyguardSecurityContainer.MODE_DEFAULT),
                eq(globalSettings),
                eq(falsingManager),
                eq(userSwitcherController),
                any(),
                eq(falsingA11yDelegate)
            )
    }

    @Test
    fun showSecurityScreen_oneHandedMode_flagEnabled_oneHandedMode() {
        testableResources.addOverride(R.bool.can_use_one_handed_bouncer, true)
        setupGetSecurityView(SecurityMode.Pattern)
        verify(view)
            .initMode(
                eq(KeyguardSecurityContainer.MODE_ONE_HANDED),
                eq(globalSettings),
                eq(falsingManager),
                eq(userSwitcherController),
                any(),
                eq(falsingA11yDelegate)
            )
    }

    @Test
    fun showSecurityScreen_twoHandedMode_flagEnabled_noOneHandedMode() {
        testableResources.addOverride(R.bool.can_use_one_handed_bouncer, true)
        setupGetSecurityView(SecurityMode.Password)
        verify(view)
            .initMode(
                eq(KeyguardSecurityContainer.MODE_DEFAULT),
                eq(globalSettings),
                eq(falsingManager),
                eq(userSwitcherController),
                any(),
                eq(falsingA11yDelegate)
            )
    }

    @Test
    fun addUserSwitcherCallback() {
        val captor = ArgumentCaptor.forClass(UserSwitcherCallback::class.java)
        setupGetSecurityView(SecurityMode.Password)
        verify(view)
            .initMode(anyInt(), any(), any(), any(), captor.capture(), eq(falsingA11yDelegate))
        captor.value.showUnlockToContinueMessage()
        viewControllerImmediately
        verify(keyguardPasswordViewControllerMock)
            .showMessage(
                /* message= */ context.getString(R.string.keyguard_unlock_to_continue),
                /* colorState= */ null,
                /* animated= */ true
            )
    }

    @Test
    fun addUserSwitchCallback() {
        underTest.onViewAttached()
        verify(userSwitcherController).addUserSwitchCallback(any())
        underTest.onViewDetached()
        verify(userSwitcherController).removeUserSwitchCallback(any())
    }

    @Test
    fun onBouncerVisibilityChanged_resetsScale() {
        underTest.onBouncerVisibilityChanged(false)
        verify(view).resetScale()
    }

    @Test
    fun showNextSecurityScreenOrFinish_setsSecurityScreenToPinAfterSimPinUnlock() {
        // GIVEN the current security method is SimPin
        whenever(keyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false)
        whenever(keyguardUpdateMonitor.getUserUnlockedWithBiometric(TARGET_USER_ID))
            .thenReturn(false)
        underTest.showSecurityScreen(SecurityMode.SimPin)

        // WHEN a request is made from the SimPin screens to show the next security method
        whenever(keyguardSecurityModel.getSecurityMode(TARGET_USER_ID)).thenReturn(SecurityMode.PIN)
        underTest.showNextSecurityScreenOrFinish(
            /* authenticated= */ true,
            TARGET_USER_ID,
            /* bypassSecondaryLockScreen= */ true,
            SecurityMode.SimPin
        )

        // THEN the next security method of PIN is set, and the keyguard is not marked as done
        verify(viewMediatorCallback, never()).keyguardDonePending(anyBoolean(), anyInt())
        verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())
        Truth.assertThat(underTest.currentSecurityMode).isEqualTo(SecurityMode.PIN)
    }

    @Test
    fun showNextSecurityScreenOrFinish_DeviceNotSecure() {
        // GIVEN the current security method is SimPin
        whenever(keyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false)
        whenever(keyguardUpdateMonitor.getUserUnlockedWithBiometric(TARGET_USER_ID))
            .thenReturn(false)
        underTest.showSecurityScreen(SecurityMode.SimPin)

        // WHEN a request is made from the SimPin screens to show the next security method
        whenever(keyguardSecurityModel.getSecurityMode(TARGET_USER_ID))
            .thenReturn(SecurityMode.None)
        whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(true)
        underTest.showNextSecurityScreenOrFinish(
            /* authenticated= */ true,
            TARGET_USER_ID,
            /* bypassSecondaryLockScreen= */ true,
            SecurityMode.SimPin
        )

        // THEN the next security method of None will dismiss keyguard.
        verify(viewMediatorCallback).keyguardDone(anyBoolean(), anyInt())
    }

    @Test
    fun showNextSecurityScreenOrFinish_ignoresCallWhenSecurityMethodHasChanged() {
        // GIVEN current security mode has been set to PIN
        underTest.showSecurityScreen(SecurityMode.PIN)

        // WHEN a request comes from SimPin to dismiss the security screens
        val keyguardDone =
            underTest.showNextSecurityScreenOrFinish(
                /* authenticated= */ true,
                TARGET_USER_ID,
                /* bypassSecondaryLockScreen= */ true,
                SecurityMode.SimPin
            )

        // THEN no action has happened, which will not dismiss the security screens
        Truth.assertThat(keyguardDone).isEqualTo(false)
        verify(keyguardUpdateMonitor, never()).getUserHasTrust(anyInt())
    }

    @Test
    fun showNextSecurityScreenOrFinish_SimPin_Swipe() {
        // GIVEN the current security method is SimPin
        whenever(keyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false)
        whenever(keyguardUpdateMonitor.getUserUnlockedWithBiometric(TARGET_USER_ID))
            .thenReturn(false)
        underTest.showSecurityScreen(SecurityMode.SimPin)

        // WHEN a request is made from the SimPin screens to show the next security method
        whenever(keyguardSecurityModel.getSecurityMode(TARGET_USER_ID))
            .thenReturn(SecurityMode.None)
        // WHEN security method is SWIPE
        whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false)
        underTest.showNextSecurityScreenOrFinish(
            /* authenticated= */ true,
            TARGET_USER_ID,
            /* bypassSecondaryLockScreen= */ true,
            SecurityMode.SimPin
        )

        // THEN the next security method of None will dismiss keyguard.
        verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())
    }

    @Test
    fun showNextSecurityScreenOrFinish_SimPinToAnotherSimPin_None() {
        // GIVEN the current security method is SimPin
        whenever(keyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false)
        whenever(keyguardUpdateMonitor.getUserUnlockedWithBiometric(TARGET_USER_ID))
            .thenReturn(false)
        underTest.showSecurityScreen(SecurityMode.SimPin)

        // WHEN a request is made from the SimPin screens to show the next security method
        whenever(keyguardSecurityModel.getSecurityMode(TARGET_USER_ID))
            .thenReturn(SecurityMode.SimPin)
        whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(true)

        underTest.showNextSecurityScreenOrFinish(
            /* authenticated= */ true,
            TARGET_USER_ID,
            /* bypassSecondaryLockScreen= */ true,
            SecurityMode.SimPin
        )

        // THEN the next security method of None will dismiss keyguard.
        verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())
    }

    @Test
    fun onSwipeUp_whenFaceDetectionIsNotRunning_initiatesFaceAuth() {
        val registeredSwipeListener = registeredSwipeListener
        whenever(keyguardUpdateMonitor.isFaceDetectionRunning).thenReturn(false)
        setupGetSecurityView(SecurityMode.Password)
        registeredSwipeListener.onSwipeUp()
        verify(keyguardUpdateMonitor).requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER)
    }

    @Test
    fun onSwipeUp_whenFaceDetectionIsRunning_doesNotInitiateFaceAuth() {
        val registeredSwipeListener = registeredSwipeListener
        whenever(keyguardUpdateMonitor.isFaceDetectionRunning).thenReturn(true)
        registeredSwipeListener.onSwipeUp()
        verify(keyguardUpdateMonitor, never())
            .requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER)
    }

    @Test
    fun onSwipeUp_whenFaceDetectionIsTriggered_hidesBouncerMessage() {
        val registeredSwipeListener = registeredSwipeListener
        whenever(
                keyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER)
            )
            .thenReturn(true)
        setupGetSecurityView(SecurityMode.Password)
        clearInvocations(viewFlipperController)
        registeredSwipeListener.onSwipeUp()
        viewControllerImmediately
        verify(keyguardPasswordViewControllerMock)
            .showMessage(/* message= */ null, /* colorState= */ null, /* animated= */ true)
    }

    @Test
    fun onSwipeUp_whenFaceDetectionIsNotTriggered_retainsBouncerMessage() {
        val registeredSwipeListener = registeredSwipeListener
        whenever(
                keyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER)
            )
            .thenReturn(false)
        setupGetSecurityView(SecurityMode.Password)
        registeredSwipeListener.onSwipeUp()
        verify(keyguardPasswordViewControllerMock, never())
            .showMessage(/* message= */ null, /* colorState= */ null, /* animated= */ true)
    }

    @Test
    fun onDensityOrFontScaleChanged() {
        val configurationListenerArgumentCaptor =
            ArgumentCaptor.forClass(ConfigurationController.ConfigurationListener::class.java)
        underTest.onViewAttached()
        verify(configurationController).addCallback(configurationListenerArgumentCaptor.capture())
        clearInvocations(viewFlipperController)
        configurationListenerArgumentCaptor.value.onDensityOrFontScaleChanged()
        verify(viewFlipperController).clearViews()
        verify(viewFlipperController)
            .asynchronouslyInflateView(
                eq(SecurityMode.PIN),
                any(),
                onViewInflatedCallbackArgumentCaptor.capture()
            )
        onViewInflatedCallbackArgumentCaptor.value.onViewInflated(inputViewController)
        verify(view).onDensityOrFontScaleChanged()
    }

    @Test
    fun onThemeChanged() {
        val configurationListenerArgumentCaptor =
            ArgumentCaptor.forClass(ConfigurationController.ConfigurationListener::class.java)
        underTest.onViewAttached()
        verify(configurationController).addCallback(configurationListenerArgumentCaptor.capture())
        clearInvocations(viewFlipperController)
        configurationListenerArgumentCaptor.value.onThemeChanged()
        verify(viewFlipperController).clearViews()
        verify(viewFlipperController)
            .asynchronouslyInflateView(
                eq(SecurityMode.PIN),
                any(),
                onViewInflatedCallbackArgumentCaptor.capture()
            )
        onViewInflatedCallbackArgumentCaptor.value.onViewInflated(inputViewController)
        verify(view).reset()
        verify(viewFlipperController).reset()
        verify(view).reloadColors()
    }

    @Test
    fun onUiModeChanged() {
        val configurationListenerArgumentCaptor =
            ArgumentCaptor.forClass(ConfigurationController.ConfigurationListener::class.java)
        underTest.onViewAttached()
        verify(configurationController).addCallback(configurationListenerArgumentCaptor.capture())
        clearInvocations(viewFlipperController)
        configurationListenerArgumentCaptor.value.onUiModeChanged()
        verify(viewFlipperController).clearViews()
        verify(viewFlipperController)
            .asynchronouslyInflateView(
                eq(SecurityMode.PIN),
                any(),
                onViewInflatedCallbackArgumentCaptor.capture()
            )
        onViewInflatedCallbackArgumentCaptor.value.onViewInflated(inputViewController)
        verify(view).reloadColors()
    }

    @Test
    fun hasDismissActions() {
        Assert.assertFalse("Action not set yet", underTest.hasDismissActions())
        underTest.setOnDismissAction(mock(), null /* cancelAction */)
        Assert.assertTrue("Action should exist", underTest.hasDismissActions())
    }

    @Test
    fun willRunDismissFromKeyguardIsTrue() {
        val action: OnDismissAction = mock()
        whenever(action.willRunAnimationOnKeyguard()).thenReturn(true)
        underTest.setOnDismissAction(action, null /* cancelAction */)
        underTest.finish(false /* strongAuth */, 0 /* currentUser */)
        Truth.assertThat(underTest.willRunDismissFromKeyguard()).isTrue()
    }

    @Test
    fun willRunDismissFromKeyguardIsFalse() {
        val action: OnDismissAction = mock()
        whenever(action.willRunAnimationOnKeyguard()).thenReturn(false)
        underTest.setOnDismissAction(action, null /* cancelAction */)
        underTest.finish(false /* strongAuth */, 0 /* currentUser */)
        Truth.assertThat(underTest.willRunDismissFromKeyguard()).isFalse()
    }

    @Test
    fun willRunDismissFromKeyguardIsFalseWhenNoDismissActionSet() {
        underTest.setOnDismissAction(null /* action */, null /* cancelAction */)
        underTest.finish(false /* strongAuth */, 0 /* currentUser */)
        Truth.assertThat(underTest.willRunDismissFromKeyguard()).isFalse()
    }

    @Test
    fun onStartingToHide() {
        underTest.onStartingToHide()
        verify(viewFlipperController)
            .getSecurityView(any(), any(), onViewInflatedCallbackArgumentCaptor.capture())
        onViewInflatedCallbackArgumentCaptor.value.onViewInflated(inputViewController)
        verify(inputViewController).onStartingToHide()
    }

    @Test
    fun gravityReappliedOnConfigurationChange() {
        // Set initial gravity
        testableResources.addOverride(R.integer.keyguard_host_view_gravity, Gravity.CENTER)
        testableResources.addOverride(R.bool.can_use_one_handed_bouncer, false)

        // Kick off the initial pass...
        underTest.onInit()
        verify(view).layoutParams = any()
        clearInvocations(view)

        // Now simulate a config change
        testableResources.addOverride(
            R.integer.keyguard_host_view_gravity,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        )
        underTest.updateResources()
        verify(view).layoutParams = any()
    }

    @Test
    fun gravityUsesOneHandGravityWhenApplicable() {
        testableResources.addOverride(R.integer.keyguard_host_view_gravity, Gravity.CENTER)
        testableResources.addOverride(
            R.integer.keyguard_host_view_one_handed_gravity,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        )

        // Start disabled.
        testableResources.addOverride(R.bool.can_use_one_handed_bouncer, false)
        underTest.onInit()
        verify(view).layoutParams =
            argThat(
                ArgumentMatcher { argument: FrameLayout.LayoutParams ->
                    argument.gravity == Gravity.CENTER
                }
                    as ArgumentMatcher<FrameLayout.LayoutParams>
            )
        clearInvocations(view)

        // And enable
        testableResources.addOverride(R.bool.can_use_one_handed_bouncer, true)
        underTest.updateResources()
        verify(view).layoutParams =
            argThat(
                ArgumentMatcher { argument: FrameLayout.LayoutParams ->
                    argument.gravity == Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                }
                    as ArgumentMatcher<FrameLayout.LayoutParams>
            )
    }

    @Test
    fun updateKeyguardPositionDelegatesToSecurityContainer() {
        underTest.updateKeyguardPosition(1.0f)
        verify(view).updatePositionByTouchX(1.0f)
    }

    @Test
    fun reinflateViewFlipper() {
        val onViewInflatedCallback = KeyguardSecurityViewFlipperController.OnViewInflatedCallback {}
        underTest.reinflateViewFlipper(onViewInflatedCallback)
        verify(viewFlipperController).clearViews()
        verify(viewFlipperController)
            .asynchronouslyInflateView(any(), any(), eq(onViewInflatedCallback))
    }

    @Test
    fun sideFpsControllerShow() {
        underTest.updateSideFpsVisibility(/* isVisible= */ true)
        verify(sideFpsController)
            .show(
                SideFpsUiRequestSource.PRIMARY_BOUNCER,
                BiometricOverlayConstants.REASON_AUTH_KEYGUARD
            )
    }

    @Test
    fun sideFpsControllerHide() {
        underTest.updateSideFpsVisibility(/* isVisible= */ false)
        verify(sideFpsController).hide(SideFpsUiRequestSource.PRIMARY_BOUNCER)
    }

    @Test
    fun setExpansion_setsAlpha() {
        underTest.setExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
        verify(view).alpha = 1f
        verify(view).translationY = 0f
    }

    @Test
    fun dismissesKeyguard_whenSceneChangesFromBouncerToGone() =
        sceneTestUtils.testScope.runTest {
            featureFlags.set(Flags.SCENE_CONTAINER, true)

            // Upon init, we have never dismisses the keyguard.
            underTest.onInit()
            runCurrent()
            verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())

            // Once the view is attached, we start listening but simply going to the bouncer scene
            // is
            // not enough to trigger a dismissal of the keyguard.
            underTest.onViewAttached()
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer, null), "reason")
            sceneTransitionStateFlow.value =
                ObservableTransitionState.Transition(
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                    flowOf(.5f)
                )
            runCurrent()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer, null), "reason")
            sceneTransitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Bouncer)
            runCurrent()
            verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())

            // While listening, going from the bouncer scene to the gone scene, does dismiss the
            // keyguard.
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone, null), "reason")
            sceneTransitionStateFlow.value =
                ObservableTransitionState.Transition(SceneKey.Bouncer, SceneKey.Gone, flowOf(.5f))
            runCurrent()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone, null), "reason")
            sceneTransitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Gone)
            runCurrent()
            verify(viewMediatorCallback).keyguardDone(anyBoolean(), anyInt())

            // While listening, moving back to the bouncer scene does not dismiss the keyguard
            // again.
            clearInvocations(viewMediatorCallback)
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer, null), "reason")
            sceneTransitionStateFlow.value =
                ObservableTransitionState.Transition(SceneKey.Gone, SceneKey.Bouncer, flowOf(.5f))
            runCurrent()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer, null), "reason")
            sceneTransitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Bouncer)
            runCurrent()
            verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())

            // Detaching the view stops listening, so moving from the bouncer scene to the gone
            // scene
            // does not dismiss the keyguard while we're not listening.
            underTest.onViewDetached()
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone, null), "reason")
            sceneTransitionStateFlow.value =
                ObservableTransitionState.Transition(SceneKey.Bouncer, SceneKey.Gone, flowOf(.5f))
            runCurrent()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone, null), "reason")
            sceneTransitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Gone)
            runCurrent()
            verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())

            // While not listening, moving back to the bouncer does not dismiss the keyguard.
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer, null), "reason")
            sceneTransitionStateFlow.value =
                ObservableTransitionState.Transition(SceneKey.Gone, SceneKey.Bouncer, flowOf(.5f))
            runCurrent()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer, null), "reason")
            sceneTransitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Bouncer)
            runCurrent()
            verify(viewMediatorCallback, never()).keyguardDone(anyBoolean(), anyInt())

            // Reattaching the view starts listening again so moving from the bouncer scene to the
            // gone
            // scene now does dismiss the keyguard again.
            underTest.onViewAttached()
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone, null), "reason")
            sceneTransitionStateFlow.value =
                ObservableTransitionState.Transition(SceneKey.Bouncer, SceneKey.Gone, flowOf(.5f))
            runCurrent()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone, null), "reason")
            sceneTransitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Gone)
            runCurrent()
            verify(viewMediatorCallback).keyguardDone(anyBoolean(), anyInt())
        }

    private val registeredSwipeListener: KeyguardSecurityContainer.SwipeListener
        get() {
            underTest.onViewAttached()
            verify(view).setSwipeListener(swipeListenerArgumentCaptor.capture())
            return swipeListenerArgumentCaptor.value
        }

    private fun setupGetSecurityView(securityMode: SecurityMode) {
        underTest.showSecurityScreen(securityMode)
        viewControllerImmediately
    }

    private val viewControllerImmediately: Unit
        get() {
            verify(viewFlipperController, atLeastOnce())
                .getSecurityView(any(), any(), onViewInflatedCallbackArgumentCaptor.capture())
            @Suppress("UNCHECKED_CAST")
            onViewInflatedCallbackArgumentCaptor.value.onViewInflated(
                keyguardPasswordViewControllerMock as KeyguardInputViewController<KeyguardInputView>
            )
        }

    companion object {
        private const val TARGET_USER_ID = 100
    }
}

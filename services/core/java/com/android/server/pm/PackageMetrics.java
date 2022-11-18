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

package com.android.server.pm;

import static android.os.Process.INVALID_UID;

import android.annotation.IntDef;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Metrics class for reporting stats to logging infrastructures like Westworld
 */
final class PackageMetrics {
    public static final int STEP_PREPARE = 1;
    public static final int STEP_SCAN = 2;
    public static final int STEP_RECONCILE = 3;
    public static final int STEP_COMMIT = 4;

    @IntDef(prefix = {"STEP_"}, value = {
            STEP_PREPARE,
            STEP_SCAN,
            STEP_RECONCILE,
            STEP_COMMIT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StepInt {
    }

    private final long mInstallStartTimestampMillis;
    private final SparseArray<InstallStep> mInstallSteps = new SparseArray<>();
    private final InstallRequest mInstallRequest;

    PackageMetrics(InstallRequest installRequest) {
        // New instance is used for tracking installation metrics only.
        // Other metrics should use static methods of this class.
        mInstallStartTimestampMillis = System.currentTimeMillis();
        mInstallRequest = installRequest;
    }

    public void onInstallSucceed(Computer snapshot) {
        // TODO(b/239722919): report to SecurityLog if on work profile or managed device
        reportInstallationStats(snapshot, true /* success */);
    }

    private void reportInstallationStats(Computer snapshot, boolean success) {
        UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        // TODO(b/249294752): do not log if adb
        final long installDurationMillis =
                System.currentTimeMillis() - mInstallStartTimestampMillis;
        // write to stats
        final Pair<int[], long[]> stepDurations = getInstallStepDurations();
        final int[] newUsers = mInstallRequest.getNewUsers();
        final int[] originalUsers = mInstallRequest.getOriginUsers();
        final String packageName = mInstallRequest.getName();
        final String installerPackageName = mInstallRequest.getInstallerPackageName();
        final int installerUid = installerPackageName == null ? INVALID_UID
                : snapshot.getPackageUid(installerPackageName, 0, 0);
        final PackageStateInternal ps = snapshot.getPackageStateInternal(packageName);
        final long versionCode = success ? 0 : ps.getVersionCode();
        final long apksSize = getApksSize(ps.getPath());

        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_INSTALLATION_SESSION_REPORTED,
                mInstallRequest.getSessionId() /* session_id */,
                success ? null : packageName /* not report package_name on success */,
                mInstallRequest.getUid() /* uid */,
                newUsers /* user_ids */,
                userManagerInternal.getUserTypesForStatsd(newUsers) /* user_types */,
                originalUsers /* original_user_ids */,
                userManagerInternal.getUserTypesForStatsd(originalUsers) /* original_user_types */,
                mInstallRequest.getReturnCode() /* public_return_code */,
                0 /* internal_error_code */,
                apksSize /* apks_size_bytes */,
                versionCode /* version_code */,
                stepDurations.first /* install_steps */,
                stepDurations.second /* step_duration_millis */,
                installDurationMillis /* total_duration_millis */,
                mInstallRequest.getInstallFlags() /* install_flags */,
                installerUid /* installer_package_uid */,
                -1 /* original_installer_package_uid */,
                mInstallRequest.getDataLoaderType() /* data_loader_type */,
                mInstallRequest.getRequireUserAction() /* user_action_required_type */,
                mInstallRequest.isInstantInstall() /* is_instant */,
                mInstallRequest.isInstallReplace() /* is_replace */,
                mInstallRequest.isInstallSystem() /* is_system */,
                mInstallRequest.isInstallInherit() /* is_inherit */,
                mInstallRequest.isInstallForUsers() /* is_installing_existing_as_user */,
                mInstallRequest.isInstallMove() /* is_move_install */,
                false /* is_staged */
        );
    }

    private long getApksSize(File apkDir) {
        // TODO(b/249294752): also count apk sizes for failed installs
        final AtomicLong apksSize = new AtomicLong();
        try (Stream<Path> walkStream = Files.walk(apkDir.toPath())) {
            walkStream.filter(p -> p.toFile().isFile()
                    && ApkLiteParseUtils.isApkFile(p.toFile())).forEach(
                            f -> apksSize.addAndGet(f.toFile().length()));
        } catch (IOException e) {
            // ignore
        }
        return apksSize.get();
    }

    public void onStepStarted(@StepInt int step) {
        mInstallSteps.put(step, new InstallStep());
    }

    public void onStepFinished(@StepInt int step) {
        final InstallStep installStep = mInstallSteps.get(step);
        if (installStep != null) {
            // Only valid if the start timestamp is set; otherwise no-op
            installStep.finish();
        }
    }

    // List of steps (e.g., 1, 2, 3) and corresponding list of durations (e.g., 200ms, 100ms, 150ms)
    private Pair<int[], long[]> getInstallStepDurations() {
        ArrayList<Integer> steps = new ArrayList<>();
        ArrayList<Long> durations = new ArrayList<>();
        for (int i = 0; i < mInstallSteps.size(); i++) {
            final long duration = mInstallSteps.valueAt(i).getDurationMillis();
            if (duration >= 0) {
                steps.add(mInstallSteps.keyAt(i));
                durations.add(mInstallSteps.valueAt(i).getDurationMillis());
            }
        }
        int[] stepsArray = new int[steps.size()];
        long[] durationsArray = new long[durations.size()];
        for (int i = 0; i < stepsArray.length; i++) {
            stepsArray[i] = steps.get(i);
            durationsArray[i] = durations.get(i);
        }
        return new Pair<>(stepsArray, durationsArray);
    }

    private static class InstallStep {
        private final long mStartTimestampMillis;
        private long mDurationMillis = -1;

        InstallStep() {
            mStartTimestampMillis = System.currentTimeMillis();
        }

        void finish() {
            mDurationMillis = System.currentTimeMillis() - mStartTimestampMillis;
        }

        long getDurationMillis() {
            return mDurationMillis;
        }
    }

    public static void onUninstallSucceeded(PackageRemovedInfo info, int deleteFlags,
            UserManagerInternal userManagerInternal) {
        if (info.mIsUpdate) {
            // Not logging uninstalls caused by app updates
            return;
        }
        final int[] removedUsers = info.mRemovedUsers;
        final int[] removedUserTypes = userManagerInternal.getUserTypesForStatsd(removedUsers);
        final int[] originalUsers = info.mOrigUsers;
        final int[] originalUserTypes = userManagerInternal.getUserTypesForStatsd(originalUsers);
        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_UNINSTALLATION_REPORTED,
                info.mUid, removedUsers, removedUserTypes, originalUsers, originalUserTypes,
                deleteFlags, PackageManager.DELETE_SUCCEEDED, info.mIsRemovedPackageSystemUpdate,
                !info.mRemovedForAllUsers);
    }
}

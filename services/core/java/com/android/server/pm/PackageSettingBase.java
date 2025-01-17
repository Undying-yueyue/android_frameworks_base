/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.UninstallReason;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.Signature;
import android.content.pm.SuspendDialogInfo;
import android.os.PersistableBundle;
import android.service.pm.PackageProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Settings base class for pending and resolved classes.
 */
public abstract class PackageSettingBase extends SettingBase {

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    public final String name;
    final String realName;

    /**
     * Path where this package was found on disk. For monolithic packages
     * this is path to single base APK file; for cluster packages this is
     * path to the cluster directory.
     */
    File codePath;
    String codePathString;
    File resourcePath;
    String resourcePathString;

    String[] usesStaticLibraries;
    long[] usesStaticLibrariesVersions;

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @Deprecated
    String legacyNativeLibraryPathString;

    /**
     * The primary CPU abi for this package.
     */
    public String primaryCpuAbiString;

    /**
     * The secondary CPU abi for this package.
     */
    public String secondaryCpuAbiString;

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@code primaryCpuAbiString} will contain the same value.
     */
    String cpuAbiOverrideString;

    long timeStamp;
    long firstInstallTime;
    long lastUpdateTime;
    long versionCode;

    boolean uidError;

    PackageSignatures signatures;

    boolean installPermissionsFixed;

    PackageKeySetData keySetData = new PackageKeySetData();

    static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();

    // Whether this package is currently stopped, thus can not be
    // started until explicitly launched by the user.
    private final SparseArray<PackageUserState> mUserState = new SparseArray<>();

    /**
     * Non-persisted value. During an "upgrade without restart", we need the set
     * of all previous code paths so we can surgically add the new APKs to the
     * active classloader. If at any point an application is upgraded with a
     * restart, this field will be cleared since the classloader would be created
     * using the full set of code paths when the package's process is started.
     */
    Set<String> mOldCodePaths;

    /** Information about how this package was installed/updated. */
    @NonNull InstallSource installSource;
    /** UUID of {@link VolumeInfo} hosting this app */
    String volumeUuid;
    /** The category of this app, as hinted by the installer */
    int categoryHint = ApplicationInfo.CATEGORY_UNDEFINED;
    /** Whether or not an update is available. Ostensibly only for instant apps. */
    boolean updateAvailable;

    IntentFilterVerificationInfo verificationInfo;

    boolean forceQueryableOverride;

    PackageSettingBase(String name, String realName, File codePath, File resourcePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString,
            long pVersionCode, int pkgFlags, int pkgPrivateFlags,
            String[] usesStaticLibraries, long[] usesStaticLibrariesVersions) {
        super(pkgFlags, pkgPrivateFlags);
        this.name = name;
        this.realName = realName;
        this.usesStaticLibraries = usesStaticLibraries;
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        this.codePath = codePath;
        this.codePathString = codePath.toString();
        this.resourcePath = resourcePath;
        this.resourcePathString = resourcePath.toString();
        this.legacyNativeLibraryPathString = legacyNativeLibraryPathString;
        this.primaryCpuAbiString = primaryCpuAbiString;
        this.secondaryCpuAbiString = secondaryCpuAbiString;
        this.cpuAbiOverrideString = cpuAbiOverrideString;
        this.versionCode = pVersionCode;
        this.signatures = new PackageSignatures();
        this.installSource = InstallSource.EMPTY;
    }

    /**
     * New instance of PackageSetting with one-level-deep cloning.
     * <p>
     * IMPORTANT: With a shallow copy, we do NOT create new contained objects.
     * This means, for example, changes to the user state of the original PackageSetting
     * will also change the user state in its copy.
     */
    PackageSettingBase(PackageSettingBase base, String realName) {
        super(base);
        name = base.name;
        this.realName = realName;
        doCopy(base);
    }

    public void setInstallerPackageName(String packageName) {
        installSource = installSource.setInstallerPackage(packageName);
    }

    public void setInstallSource(InstallSource installSource) {
        this.installSource = Objects.requireNonNull(installSource);
    }

    void removeInstallerPackage(String packageName) {
        installSource = installSource.removeInstallerPackage(packageName);
    }

    public void setIsOrphaned(boolean isOrphaned) {
        installSource = installSource.setIsOrphaned(isOrphaned);
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    public String getVolumeUuid() {
        return volumeUuid;
    }

    public void setTimeStamp(long newStamp) {
        timeStamp = newStamp;
    }

    public void setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
    }

    public String getInstallerPackageName() {
        return installSource.installerPackageName;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public boolean isSharedUser() {
        return false;
    }

    public Signature[] getSignatures() {
        return signatures.mSigningDetails.signatures;
    }

    public PackageParser.SigningDetails getSigningDetails() {
        return signatures.mSigningDetails;
    }

    /**
     * Makes a shallow copy of the given package settings.
     *
     * NOTE: For some fields [such as keySetData, signatures, mUserState, verificationInfo, etc...],
     * the original object is copied and a new one is not created.
     */
    public void copyFrom(PackageSettingBase orig) {
        super.copyFrom(orig);
        doCopy(orig);
    }

    private void doCopy(PackageSettingBase orig) {
        codePath = orig.codePath;
        codePathString = orig.codePathString;
        cpuAbiOverrideString = orig.cpuAbiOverrideString;
        firstInstallTime = orig.firstInstallTime;
        installPermissionsFixed = orig.installPermissionsFixed;
        installSource = orig.installSource;
        keySetData = orig.keySetData;
        lastUpdateTime = orig.lastUpdateTime;
        legacyNativeLibraryPathString = orig.legacyNativeLibraryPathString;
        // Intentionally skip mOldCodePaths; it's not relevant for copies
        primaryCpuAbiString = orig.primaryCpuAbiString;
        resourcePath = orig.resourcePath;
        resourcePathString = orig.resourcePathString;
        secondaryCpuAbiString = orig.secondaryCpuAbiString;
        signatures = orig.signatures;
        timeStamp = orig.timeStamp;
        uidError = orig.uidError;
        mUserState.clear();
        for (int i = 0; i < orig.mUserState.size(); i++) {
            mUserState.put(orig.mUserState.keyAt(i), orig.mUserState.valueAt(i));
        }
        verificationInfo = orig.verificationInfo;
        versionCode = orig.versionCode;
        volumeUuid = orig.volumeUuid;
        categoryHint = orig.categoryHint;
        usesStaticLibraries = orig.usesStaticLibraries != null
                ? Arrays.copyOf(orig.usesStaticLibraries,
                        orig.usesStaticLibraries.length) : null;
        usesStaticLibrariesVersions = orig.usesStaticLibrariesVersions != null
                ? Arrays.copyOf(orig.usesStaticLibrariesVersions,
                       orig.usesStaticLibrariesVersions.length) : null;
        updateAvailable = orig.updateAvailable;
        forceQueryableOverride = orig.forceQueryableOverride;
    }

    @VisibleForTesting
    PackageUserState modifyUserState(int userId) {
        PackageUserState state = mUserState.get(userId);
        if (state == null) {
            state = new PackageUserState();
            mUserState.put(userId, state);
        }
        return state;
    }

    public PackageUserState readUserState(int userId) {
        PackageUserState state = mUserState.get(userId);
        if (state == null) {
            return DEFAULT_USER_STATE;
        }
        state.categoryHint = categoryHint;
        return state;
    }

    void setEnabled(int state, int userId, String callingPackage) {
        PackageUserState st = modifyUserState(userId);
        st.enabled = state;
        st.lastDisableAppCaller = callingPackage;
    }

    int getEnabled(int userId) {
        return readUserState(userId).enabled;
    }

    String getLastDisabledAppCaller(int userId) {
        return readUserState(userId).lastDisableAppCaller;
    }

    void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    int getInstallReason(int userId) {
        return readUserState(userId).installReason;
    }

    void setInstallReason(int installReason, int userId) {
        modifyUserState(userId).installReason = installReason;
    }

    int getUninstallReason(int userId) {
        return readUserState(userId).uninstallReason;
    }

    void setUninstallReason(@UninstallReason int uninstallReason, int userId) {
        modifyUserState(userId).uninstallReason = uninstallReason;
    }

    void setOverlayPaths(List<String> overlayPaths, int userId) {
        modifyUserState(userId).setOverlayPaths(overlayPaths == null ? null :
            overlayPaths.toArray(new String[overlayPaths.size()]));
    }

    String[] getOverlayPaths(int userId) {
        return readUserState(userId).getOverlayPaths();
    }

    void setOverlayPathsForLibrary(String libName, List<String> overlayPaths, int userId) {
        modifyUserState(userId).setSharedLibraryOverlayPaths(libName,
                overlayPaths == null ? null : overlayPaths.toArray(new String[0]));
    }

    Map<String, String[]> getOverlayPathsForLibrary(int userId) {
        return readUserState(userId).getSharedLibraryOverlayPaths();
    }

    /** Only use for testing. Do NOT use in production code. */
    @VisibleForTesting
    SparseArray<PackageUserState> getUserState() {
        return mUserState;
    }

    boolean isAnyInstalled(int[] users) {
        for (int user: users) {
            if (readUserState(user).installed) {
                return true;
            }
        }
        return false;
    }

    int[] queryInstalledUsers(int[] users, boolean installed) {
        int num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                res[num] = user;
                num++;
            }
        }
        return res;
    }

    long getCeDataInode(int userId) {
        return readUserState(userId).ceDataInode;
    }

    void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).ceDataInode = ceDataInode;
    }

    boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    boolean getNotLaunched(int userId) {
        return readUserState(userId).notLaunched;
    }

    void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).notLaunched = stop;
    }

    boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    int getDistractionFlags(int userId) {
        return readUserState(userId).distractionFlags;
    }

    void setDistractionFlags(int distractionFlags, int userId) {
        modifyUserState(userId).distractionFlags = distractionFlags;
    }

    boolean getSuspended(int userId) {
        return readUserState(userId).suspended;
    }

    boolean isSuspendedBy(String suspendingPackage, int userId) {
        final PackageUserState state = readUserState(userId);
        return state.suspendParams != null && state.suspendParams.containsKey(suspendingPackage);
    }

    void addOrUpdateSuspension(String suspendingPackage, SuspendDialogInfo dialogInfo,
            PersistableBundle appExtras, PersistableBundle launcherExtras, int userId) {
        final PackageUserState existingUserState = modifyUserState(userId);
        final PackageUserState.SuspendParams newSuspendParams =
                PackageUserState.SuspendParams.getInstanceOrNull(dialogInfo, appExtras,
                        launcherExtras);
        if (existingUserState.suspendParams == null) {
            existingUserState.suspendParams = new ArrayMap<>();
        }
        existingUserState.suspendParams.put(suspendingPackage, newSuspendParams);
        existingUserState.suspended = true;
    }

    void removeSuspension(String suspendingPackage, int userId) {
        final PackageUserState existingUserState = modifyUserState(userId);
        if (existingUserState.suspendParams != null) {
            existingUserState.suspendParams.remove(suspendingPackage);
            if (existingUserState.suspendParams.size() == 0) {
                existingUserState.suspendParams = null;
            }
        }
        existingUserState.suspended = (existingUserState.suspendParams != null);
    }

    void removeSuspension(Predicate<String> suspendingPackagePredicate, int userId) {
        final PackageUserState existingUserState = modifyUserState(userId);
        if (existingUserState.suspendParams != null) {
            for (int i = existingUserState.suspendParams.size() - 1; i >= 0; i--) {
                final String suspendingPackage = existingUserState.suspendParams.keyAt(i);
                if (suspendingPackagePredicate.test(suspendingPackage)) {
                    existingUserState.suspendParams.removeAt(i);
                }
            }
            if (existingUserState.suspendParams.size() == 0) {
                existingUserState.suspendParams = null;
            }
        }
        existingUserState.suspended = (existingUserState.suspendParams != null);
    }

    public boolean getInstantApp(int userId) {
        return readUserState(userId).instantApp;
    }

    void setInstantApp(boolean instantApp, int userId) {
        modifyUserState(userId).instantApp = instantApp;
    }

    boolean getVirtulalPreload(int userId) {
        return readUserState(userId).virtualPreload;
    }

    void setVirtualPreload(boolean virtualPreload, int userId) {
        modifyUserState(userId).virtualPreload = virtualPreload;
    }

    void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean hidden, int distractionFlags, boolean suspended,
            ArrayMap<String, PackageUserState.SuspendParams> suspendParams, boolean instantApp,
            boolean virtualPreload, String lastDisableAppCaller,
            ArraySet<String> enabledComponents, ArraySet<String> disabledComponents,
            int domainVerifState, int linkGeneration, int installReason, int uninstallReason,
            String harmfulAppWarning) {
        PackageUserState state = modifyUserState(userId);
        state.ceDataInode = ceDataInode;
        state.enabled = enabled;
        state.installed = installed;
        state.stopped = stopped;
        state.notLaunched = notLaunched;
        state.hidden = hidden;
        state.distractionFlags = distractionFlags;
        state.suspended = suspended;
        state.suspendParams = suspendParams;
        state.lastDisableAppCaller = lastDisableAppCaller;
        state.enabledComponents = enabledComponents;
        state.disabledComponents = disabledComponents;
        state.domainVerificationStatus = domainVerifState;
        state.appLinkGeneration = linkGeneration;
        state.installReason = installReason;
        state.uninstallReason = uninstallReason;
        state.instantApp = instantApp;
        state.virtualPreload = virtualPreload;
        state.harmfulAppWarning = harmfulAppWarning;
    }

    void setUserState(int userId, PackageUserState otherState) {
        setUserState(userId, otherState.ceDataInode, otherState.enabled, otherState.installed,
                otherState.stopped, otherState.notLaunched, otherState.hidden,
                otherState.distractionFlags, otherState.suspended, otherState.suspendParams,
                otherState.instantApp,
                otherState.virtualPreload, otherState.lastDisableAppCaller,
                otherState.enabledComponents, otherState.disabledComponents,
                otherState.domainVerificationStatus, otherState.appLinkGeneration,
                otherState.installReason, otherState.uninstallReason, otherState.harmfulAppWarning);
    }

    ArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).enabledComponents;
    }

    ArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).disabledComponents;
    }

    void setEnabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components;
    }

    void setDisabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components;
    }

    void setEnabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    void setDisabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    PackageUserState modifyUserStateComponents(int userId, boolean disabled, boolean enabled) {
        PackageUserState state = modifyUserState(userId);
        if (disabled && state.disabledComponents == null) {
            state.disabledComponents = new ArraySet<String>(1);
        }
        if (enabled && state.enabledComponents == null) {
            state.enabledComponents = new ArraySet<String>(1);
        }
        return state;
    }

    void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false).disabledComponents.add(componentClassName);
    }

    void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true).enabledComponents.add(componentClassName);
    }

    boolean enableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, false, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents.add(componentClassName);
        return changed;
    }

    boolean disableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, false);
        boolean changed = state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        changed |= state.disabledComponents.add(componentClassName);
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserState state = readUserState(userId);
        if (state.enabledComponents != null && state.enabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (state.disabledComponents != null
                && state.disabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    void removeUser(int userId) {
        mUserState.delete(userId);
    }

    public int[] getNotInstalledUserIds() {
        int count = 0;
        int userStateCount = mUserState.size();
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserState.valueAt(i).installed) {
                count++;
            }
        }
        if (count == 0) return EMPTY_INT_ARRAY;
        int[] excludedUserIds = new int[count];
        int idx = 0;
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserState.valueAt(i).installed) {
                excludedUserIds[idx++] = mUserState.keyAt(i);
            }
        }
        return excludedUserIds;
    }

    IntentFilterVerificationInfo getIntentFilterVerificationInfo() {
        return verificationInfo;
    }

    void setIntentFilterVerificationInfo(IntentFilterVerificationInfo info) {
        verificationInfo = info;
    }

    // Returns a packed value as a long:
    //
    // high 'int'-sized word: link status: undefined/ask/never/always.
    // low 'int'-sized word: relative priority among 'always' results.
    long getDomainVerificationStatusForUser(int userId) {
        PackageUserState state = readUserState(userId);
        long result = (long) state.appLinkGeneration;
        result |= ((long) state.domainVerificationStatus) << 32;
        return result;
    }

    void setDomainVerificationStatusForUser(final int status, int generation, int userId) {
        PackageUserState state = modifyUserState(userId);
        state.domainVerificationStatus = status;
        if (status == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
            state.appLinkGeneration = generation;
        }
    }

    void clearDomainVerificationStatusForUser(int userId) {
        modifyUserState(userId).domainVerificationStatus =
                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }

    protected void writeUsersInfoToProto(ProtoOutputStream proto, long fieldId) {
        int count = mUserState.size();
        for (int i = 0; i < count; i++) {
            final long userToken = proto.start(fieldId);
            final int userId = mUserState.keyAt(i);
            final PackageUserState state = mUserState.valueAt(i);
            proto.write(PackageProto.UserInfoProto.ID, userId);
            final int installType;
            if (state.instantApp) {
                installType = PackageProto.UserInfoProto.INSTANT_APP_INSTALL;
            } else if (state.installed) {
                installType = PackageProto.UserInfoProto.FULL_APP_INSTALL;
            } else {
                installType = PackageProto.UserInfoProto.NOT_INSTALLED_FOR_USER;
            }
            proto.write(PackageProto.UserInfoProto.INSTALL_TYPE, installType);
            proto.write(PackageProto.UserInfoProto.IS_HIDDEN, state.hidden);
            proto.write(PackageProto.UserInfoProto.DISTRACTION_FLAGS, state.distractionFlags);
            proto.write(PackageProto.UserInfoProto.IS_SUSPENDED, state.suspended);
            if (state.suspended) {
                for (int j = 0; j < state.suspendParams.size(); j++) {
                    proto.write(PackageProto.UserInfoProto.SUSPENDING_PACKAGE,
                            state.suspendParams.keyAt(j));
                }
            }
            proto.write(PackageProto.UserInfoProto.IS_STOPPED, state.stopped);
            proto.write(PackageProto.UserInfoProto.IS_LAUNCHED, !state.notLaunched);
            proto.write(PackageProto.UserInfoProto.ENABLED_STATE, state.enabled);
            proto.write(
                    PackageProto.UserInfoProto.LAST_DISABLED_APP_CALLER,
                    state.lastDisableAppCaller);
            proto.end(userToken);
        }
    }

    void setHarmfulAppWarning(int userId, String harmfulAppWarning) {
        PackageUserState userState = modifyUserState(userId);
        userState.harmfulAppWarning = harmfulAppWarning;
    }

    String getHarmfulAppWarning(int userId) {
        PackageUserState userState = readUserState(userId);
        return userState.harmfulAppWarning;
    }

    /**
     * @see PackageUserState#overrideLabelAndIcon(ComponentName, String, Integer)
     *
     * @param userId the specific user to change the label/icon for
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean overrideNonLocalizedLabelAndIcon(@NonNull ComponentName component,
            @Nullable String label, @Nullable Integer icon, @UserIdInt int userId) {
        return modifyUserState(userId).overrideLabelAndIcon(component, label, icon);
    }

    /**
     * @see PackageUserState#resetOverrideComponentLabelIcon()
     *
     * @param userId the specific user to reset
     */
    public void resetOverrideComponentLabelIcon(@UserIdInt int userId) {
        modifyUserState(userId).resetOverrideComponentLabelIcon();
    }

    protected PackageSettingBase updateFrom(PackageSettingBase other) {
        super.copyFrom(other);
        this.codePath = other.codePath;
        this.codePathString = other.codePathString;
        this.resourcePath = other.resourcePath;
        this.resourcePathString = other.resourcePathString;
        this.usesStaticLibraries = other.usesStaticLibraries;
        this.usesStaticLibrariesVersions = other.usesStaticLibrariesVersions;
        this.legacyNativeLibraryPathString = other.legacyNativeLibraryPathString;
        this.primaryCpuAbiString = other.primaryCpuAbiString;
        this.secondaryCpuAbiString = other.secondaryCpuAbiString;
        this.cpuAbiOverrideString = other.cpuAbiOverrideString;
        this.timeStamp = other.timeStamp;
        this.firstInstallTime = other.firstInstallTime;
        this.lastUpdateTime = other.lastUpdateTime;
        this.versionCode = other.versionCode;
        this.uidError = other.uidError;
        this.signatures = other.signatures;
        this.installPermissionsFixed = other.installPermissionsFixed;
        this.keySetData = other.keySetData;
        this.installSource = other.installSource;
        this.volumeUuid = other.volumeUuid;
        this.categoryHint = other.categoryHint;
        this.updateAvailable = other.updateAvailable;
        this.verificationInfo = other.verificationInfo;
        this.forceQueryableOverride = other.forceQueryableOverride;

        if (mOldCodePaths != null) {
            if (other.mOldCodePaths != null) {
                mOldCodePaths.clear();
                mOldCodePaths.addAll(other.mOldCodePaths);
            } else {
                mOldCodePaths = null;
            }
        }
        mUserState.clear();
        for (int i = 0; i < other.mUserState.size(); i++) {
            mUserState.put(other.mUserState.keyAt(i), other.mUserState.valueAt(i));
        }
        return this;
    }
}

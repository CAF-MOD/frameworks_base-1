/*
 * Copyright (C) 2019 BaikalOS
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

package com.android.internal.baikalos;

import android.util.Slog;

import android.text.TextUtils;

import android.os.UserHandle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.SystemProperties;

import android.content.Context;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import android.app.AppOpsManager;

import android.net.Uri;

import android.database.ContentObserver;

import android.provider.Settings;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class AppProfileSettings extends ContentObserver {

    private static final String TAG = "Baikal.AppSettings";

    private static AppProfileSettings sInstance;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final TextUtils.StringSplitter mSplitter = new TextUtils.SimpleStringSplitter('|');

    private HashMap<String, AppProfile> _profilesByPackgeName = new HashMap<String,AppProfile> ();
    private HashMap<Integer, AppProfile> _profiles = new HashMap<Integer,AppProfile> ();

    private static HashMap<String, AppProfile> _staticProfilesByPackgeName = null; 
    private static HashMap<Integer, AppProfile> _staticProfiles = null;


    private final PackageManager mPackageManager;
    private final PowerWhitelistBackend mBackend;
    private AppOpsManager mAppOpsManager;


    public interface IAppProfileSettingsNotifier {
        void onAppProfileSettingsChanged();
    }

    private IAppProfileSettingsNotifier mNotifier = null;

    private AppProfileSettings(Handler handler,Context context, ContentResolver resolver, IAppProfileSettingsNotifier notifier) {
        super(handler);
        mContext = context;
        mResolver = resolver;
        mNotifier = notifier;

        mPackageManager = mContext.getPackageManager();

        mBackend = PowerWhitelistBackend.getInstance(mContext);
        mBackend.refreshList(); 

        try {
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_APP_PROFILES),
                    false, this);

        } catch( Exception e ) {
        }

        updateConstants();
        updateProfilesAndOps();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateConstants();
    }


    private void updateProfilesAndOps() {
        synchronized (this) {
            updateProfilesAndOpsLocked();
        }
    }

    private void updateProfilesAndOpsLocked() {
        boolean changed = false;
        List<PackageInfo> installedAppInfo = mPackageManager.getInstalledPackages(/*PackageManager.GET_PERMISSIONS*/0);
        for (PackageInfo info : installedAppInfo) {
            AppProfile profile = getProfile(info.packageName);
            if( profile != null ) {
                changed = updateSystemSettingsProfile(profile);
            } else {

                int runInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_IN_BACKGROUND,
                    info.applicationInfo.uid, info.packageName);        

                int runAnyInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                    info.applicationInfo.uid, info.packageName);

                boolean whitelisted = mBackend.isWhitelisted(info.packageName);

                if( runInBackground == AppOpsManager.MODE_ALLOWED &&
                    runAnyInBackground == AppOpsManager.MODE_ALLOWED &&
                    !whitelisted ) continue;

                profile = new AppProfile();
                profile.mPackageName = info.packageName;
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Sync profile for packageName=" + profile.mPackageName + ", uid=" + info.applicationInfo.uid);
                updateProfileSystemSettings(profile);
                updateProfileLocked(profile);
                changed = true;
            }
        }
        if( changed ) {
            saveLocked();
        }
    }

    private void updateRemovedProfiles() {
        boolean changed = false;
        List<PackageInfo> installedAppInfo = mPackageManager.getInstalledPackages(/*PackageManager.GET_PERMISSIONS*/0);
        for (PackageInfo info : installedAppInfo) {
            AppProfile profile = getProfile(info.packageName);
            if( profile != null ) {
                continue;
            } else {

                int runInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_IN_BACKGROUND,
                    info.applicationInfo.uid, info.packageName);        

                int runAnyInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                    info.applicationInfo.uid, info.packageName);

                boolean whitelisted = mBackend.isWhitelisted(info.packageName);

                if( !SystemProperties.get("b.spf."+info.packageName,"0").equals("0") ) {
                    setZygoteSettings("b.spf.",info.packageName,"0");
                }

                if( runInBackground == AppOpsManager.MODE_ALLOWED &&
                    runAnyInBackground == AppOpsManager.MODE_ALLOWED &&
                    !whitelisted ) continue;

                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Clearing background constraints packageName=" + info.packageName + 
                            ", uid=" + info.applicationInfo.uid);


                if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) 
                    setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,info.applicationInfo.uid, info.packageName,AppOpsManager.MODE_ALLOWED); 
                if( runInBackground != AppOpsManager.MODE_ALLOWED ) 
                    setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,info.applicationInfo.uid, info.packageName,AppOpsManager.MODE_ALLOWED); 
                if(whitelisted) {
                    synchronized(mBackend) {
                        mBackend.removeApp(info.packageName);
                    }
                }
            }
        }
    }

    private void updateConstants() {
        synchronized (this) {
            updateConstantsLocked();
        }
        if( mNotifier != null ) {
            mNotifier.onAppProfileSettingsChanged();
        }
    }

    private void updateConstantsLocked() {

        synchronized(mBackend) {
           mBackend.refreshList();
        }

        try {
            String appProfiles = Settings.Global.getString(mResolver,
                    Settings.Global.BAIKALOS_APP_PROFILES);

            if( appProfiles == null ) {
                Slog.e(TAG, "Empty profiles settings");
                return ;
            }


            try {
                mSplitter.setString(appProfiles);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad profiles settings", e);
                return ;
            }

            _profiles = new HashMap<Integer,AppProfile> ();
            _profilesByPackgeName = new HashMap<String,AppProfile> ();

            for(String profileString:mSplitter) {
                AppProfile profile = AppProfile.Deserialize(profileString);
                if( profile != null  ) {
                    if( !_profilesByPackgeName.containsKey(profile.mPackageName)  ) {
                        _profilesByPackgeName.put(profile.mPackageName, profile);
			
	                    int uid = getAppUidLocked(profile.mPackageName);
	                    if( uid == -1 ) continue;
                        if( uid < Process.FIRST_APPLICATION_UID ) continue;
                        if( !_profiles.containsKey(uid) ) {
                            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Load profile for packageName=" + profile.mPackageName + ", uid=" + uid);
                            _profiles.put(uid, profile);
                        } else {
                            Slog.e(TAG, "Duplicated profile for uid=" + uid + ", packageName=" + profile.mPackageName);
                        }
                    }
                    updateProfileSystemSettings(profile);
                    updateSystemSettingsProfile(profile);
                    updateProfileZygoteSettings(profile);
                }
            }

            updateRemovedProfiles();

        } catch (Exception e) {
            Slog.e(TAG, "Bad BaikalService settings", e);
            return;
        }

        _staticProfilesByPackgeName =  _profilesByPackgeName;
        _staticProfiles = _profiles;
    }

    private void updateProfileZygoteSettings(AppProfile profile) {
        setZygoteSettings("b.spf.",profile.mPackageName,"" + profile.mSpoofDevice);
    }

    private void setZygoteSettings(String propPrefix, String packageName, String value) {
        try {
            SystemProperties.set(propPrefix + packageName,value);
        } catch(Exception e) {
            Slog.e(TAG, "BaikalService: Can't set Zygote settings:" + packageName, e);
        }
    }


    private void updateProfileSystemSettings(AppProfile profile) {


        int uid = getAppUidLocked(profile.mPackageName);
        int runInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_IN_BACKGROUND,
                uid, profile.mPackageName);        

        int runAnyInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                uid, profile.mPackageName);

        if( mBackend.isSysWhitelisted(profile.mPackageName) ) { 
            profile.mBackground = -1; 
            if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
        }
        else if( mBackend.isDefaultActiveApp(profile.mPackageName) ) { 
            profile.mBackground = -1; 
            if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
        }
        else if( mBackend.isWhitelisted(profile.mPackageName) ) {
            profile.mBackground = -1; 
            if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
        }
        
        else if( uid != -1 && runInBackground != AppOpsManager.MODE_ALLOWED ) {
            if( profile.mBackground < 2 ) profile.mBackground = 2; 
        }

        else if( uid != -1 && runAnyInBackground != AppOpsManager.MODE_ALLOWED ) {
            if( profile.mBackground < 1 ) profile.mBackground = 1; 
        }
    }

    private boolean updateSystemSettingsProfile(AppProfile profile) {

        int uid = getAppUidLocked(profile.mPackageName);

        int runInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_IN_BACKGROUND,
                uid, profile.mPackageName);        

        int runAnyInBackground = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                uid, profile.mPackageName);


        if( mBackend.isSysWhitelisted(profile.mPackageName) ) { 
            profile.mBackground = -1; 
            if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            return true;
        }
        else if( mBackend.isDefaultActiveApp(profile.mPackageName) ) { 
            profile.mBackground = -1; 
            if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
            return true;
        }
        switch(profile.mBackground) {
            case -1:
                if( !mBackend.isWhitelisted(profile.mPackageName) ) {
                    synchronized(mBackend) {
                        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Add to whitelist packageName=" + profile.mPackageName + ", uid=" + uid);
                        mBackend.addApp(profile.mPackageName);
                    }
                }
                if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
                if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
                break;

            case 0:
                if( runAnyInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
                if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
                if( mBackend.isWhitelisted(profile.mPackageName) ) {
                    synchronized(mBackend) {
                        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Remove from whitelist packageName=" + profile.mPackageName + ", uid=" + uid);
                        mBackend.removeApp(profile.mPackageName);
                    }
                }
                break;

            case 1:
                if( runAnyInBackground != AppOpsManager.MODE_IGNORED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_IGNORED); 
                if( runInBackground != AppOpsManager.MODE_ALLOWED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_ALLOWED); 
                if( mBackend.isWhitelisted(profile.mPackageName) ) {
                    synchronized(mBackend) {
                        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Remove from whitelist packageName=" + profile.mPackageName + ", uid=" + uid);
                        mBackend.removeApp(profile.mPackageName);
                    }
                }
                break;

            case 2:
                if( runAnyInBackground != AppOpsManager.MODE_IGNORED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_IGNORED); 
                if( runInBackground != AppOpsManager.MODE_IGNORED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_IGNORED); 
                if( mBackend.isWhitelisted(profile.mPackageName) ) {
                    synchronized(mBackend) {
                        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Remove from whitelist packageName=" + profile.mPackageName + ", uid=" + uid);
                        mBackend.removeApp(profile.mPackageName);
                    }
                }
                break;

            case 3:
                if( runAnyInBackground != AppOpsManager.MODE_IGNORED ) setBackgroundMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_IGNORED); 
                if( runInBackground != AppOpsManager.MODE_IGNORED ) setBackgroundMode(AppOpsManager.OP_RUN_IN_BACKGROUND,uid, profile.mPackageName,AppOpsManager.MODE_IGNORED); 
                if( mBackend.isWhitelisted(profile.mPackageName) ) {
                    synchronized(mBackend) {
                        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Remove from whitelist packageName=" + profile.mPackageName + ", uid=" + uid);
                        mBackend.removeApp(profile.mPackageName);
                    }
                }
                break;
        }
        return false;
    }
    
    private void setBackgroundMode(int op, int uid, String packageName, int mode) {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Set AppOp " + op + " for packageName=" + packageName + ", uid=" + uid + " to " + mode);
        if( uid != -1 ) {
            getAppOpsManager().setMode(op, uid, packageName, mode);
        }
    }
 

    public void saveLocked() {
        String val = "";

        synchronized(mBackend) {
            mBackend.refreshList();
        }

//        for(AppProfile profile: _profiles) {

        for(Map.Entry<String, AppProfile> entry : _profilesByPackgeName.entrySet()) {
            updateSystemSettingsProfile(entry.getValue());
            if( entry.getValue().isDefault() ) { 
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Skip saving default profile for packageName=" + entry.getValue().mPackageName);
                continue;
            }
            int uid = getAppUidLocked(entry.getValue().mPackageName);
            if( uid == -1 ) { 
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Skip saving profile for packageName=" + entry.getValue().mPackageName + ". Seems app deleted");
                continue;
            }

            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Save profile for packageName=" + entry.getValue().mPackageName);
            String entryString = entry.getValue().Serialize();
            if( entryString != null ) val += entryString + "|";
        } 
        Settings.Global.putString(mResolver,
            Settings.Global.BAIKALOS_APP_PROFILES,val);
    }


    public static void resetAll(ContentResolver resolver) {

        Settings.Global.putString(resolver,
            Settings.Global.BAIKALOS_APP_PROFILES,"");

    }

    public static void saveBackup(ContentResolver resolver) {
       
        String appProfiles = Settings.Global.getString(resolver,
                Settings.Global.BAIKALOS_APP_PROFILES);

        Settings.Global.putString(resolver,
            Settings.Global.BAIKALOS_APP_PROFILES_BACKUP,appProfiles);
    }

    public static void restoreBackup(ContentResolver resolver) {
       
        String appProfiles = Settings.Global.getString(resolver,
                Settings.Global.BAIKALOS_APP_PROFILES_BACKUP);

        Settings.Global.putString(resolver,
            Settings.Global.BAIKALOS_APP_PROFILES,appProfiles);
    }


    public AppProfile getProfileLocked(String packageName) {
        if( packageName != null ) {
	        return _profilesByPackgeName.get(packageName);
        }
        return null;
    }

    public AppProfile getProfileLocked(int uid, String packageName) {
        if( uid < 0 ) return null;
        if( uid < Process.FIRST_APPLICATION_UID ) {
            if( packageName != null ) {
                return _profilesByPackgeName.get(packageName);
            }
            return null;
        }
	    return _profiles.get(uid);
    }

    public void updateProfileLocked(AppProfile profile) {
        if( !_profilesByPackgeName.containsKey(profile.mPackageName) ) {
            _profilesByPackgeName.put(profile.mPackageName, profile);
        } else {
            _profilesByPackgeName.replace(profile.mPackageName, profile);
        }
	int uid = getAppUidLocked(profile.mPackageName);
	if( uid == -1 ) return;
        if( !_profiles.containsKey(uid) ) {
            _profiles.put(uid, profile);
        } else {
            _profiles.replace(uid, profile);
        }
	
    }

    private int getAppUidLocked(String packageName) {
	    int uid = -1;

        final PackageManager pm = mContext.getPackageManager();

        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName,
                    PackageManager.MATCH_ALL);
            if( ai != null ) {
                return ai.uid;
            }
        } catch(Exception e) {
            Slog.i(TAG,"Package " + packageName + " not found on this device");
        }
        return uid;
    }

    public void save() {
        synchronized(this) {
            saveLocked();
        }
    }

    public AppProfile getProfile(String packageName) {
        synchronized(this) {
            return getProfileLocked(packageName);
        }
    }

    public AppProfile getProfile(int uid, String packageName) {
        synchronized(this) {
            return getProfileLocked(uid, packageName);
        }
    }

    public void updateProfile(AppProfile profile) {
        synchronized(this) {
            updateProfileLocked(profile);
        }
    }

    public static AppProfile getProfileStatic(String packageName) {
        if( _staticProfilesByPackgeName == null ) return null;
	    return _staticProfilesByPackgeName.get(packageName);
    }

    public static AppProfile getProfileStatic(int uid) {
        if( _staticProfiles == null ) return null;
	    return _staticProfiles.get(uid);
    }

    AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        }
        return mAppOpsManager;
    }

    public static AppProfileSettings getInstance(Handler handler,Context context, ContentResolver resolver, IAppProfileSettingsNotifier notifier) {
        if (sInstance == null) {
            sInstance = new AppProfileSettings(handler,context,resolver,notifier);
        }
        return sInstance;
    }

    /*

    private static Object volumeMultipliersLock = new Object();

    private static HashMap<Integer, Integer> _volumeMultipliers = null;
    private static HashMap<Integer, Boolean> _audioFocusChange = null;

    private void updateVolumeMultipliers() {

        //_volumeMultipliers
        HashMap<Integer, Integer> volumeMultipliers = new HashMap<Integer, Integer>();
        HashMap<Integer, Boolean> audioFocusChange = new HashMap<Integer, Boolean>();

        synchronized(this) {
            for(Map.Entry<Integer, AppProfile> entry: _profiles.entrySet()) {
                if( entry.getValue().mVolumeMultiplier >=0 && 
                    entry.getValue().mVolumeMultiplier <= 200 ) {
                    Slog.e(TAG, "mVolumeMultiplier for uid=" + entry.getKey() + ", mul=" + entry.getValue().mVolumeMultiplier);
                    volumeMultipliers.put(entry.getKey(),entry.getValue().mVolumeMultiplier);
                }
                if( entry.getValue().mIgnoreAudioFocus ) {
                    Slog.e(TAG, "mIgnoreAudioFocus for uid=" + entry.getKey() + ", fc=" + entry.getValue().mIgnoreAudioFocus);
                    audioFocusChange.put(entry.getKey(),entry.getValue().mIgnoreAudioFocus);
                }
            }
        }
        synchronized(volumeMultipliersLock){
            _volumeMultipliers = volumeMultipliers;
            _audioFocusChange = audioFocusChange;
        }
    }

    public static float getVolumeMultiplier(int uid) {
        synchronized(volumeMultipliersLock){
            if( _volumeMultipliers == null ) { 
                Slog.e(TAG, "getVolumeMultiplier for uid=" + uid + ", mul=NA");
                return 100.0F;
            }
            Integer mul = _volumeMultipliers.get(uid);
            Slog.e(TAG, "getVolumeMultiplier for uid=" + uid + ", mul=" + mul);
            if( mul == null ) { 
                Slog.e(TAG, "getVolumeMultiplier for uid=" + uid + ", mul=ND");
                return 100.0F;
            }
            return ((float)mul)/100.0F;
        }
    }

    public static Boolean isIgnoreAudioFocus(int uid) {
        synchronized(volumeMultipliersLock){
            if( _audioFocusChange == null ) return false;
            return _audioFocusChange.get(uid);
        }
    }
    */
}

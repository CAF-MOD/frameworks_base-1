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

import android.content.Context;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;

import android.net.Uri;

import android.database.ContentObserver;

import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

public class AppProfileSettings extends ContentObserver {

    private static final String TAG = "Baikal.AppSettings";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final TextUtils.StringSplitter mSplitter = new TextUtils.SimpleStringSplitter('|');

    private HashMap<String, AppProfile> _profilesByPackgeName = new HashMap<String,AppProfile> ();
    private HashMap<Integer, AppProfile> _profiles = new HashMap<Integer,AppProfile> ();

    private static HashMap<String, AppProfile> _staticProfilesByPackgeName = null; 
    private static HashMap<Integer, AppProfile> _staticProfiles = null;


    public interface IAppProfileSettingsNotifier {
        void onAppProfileSettingsChanged();
    }

    private IAppProfileSettingsNotifier mNotifier = null;

    public AppProfileSettings(Handler handler,Context context, ContentResolver resolver, IAppProfileSettingsNotifier notifier) {
        super(handler);
        mContext = context;
        mResolver = resolver;
        mNotifier = notifier;

        try {
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_APP_PROFILES),
                    false, this);

        } catch( Exception e ) {
        }

        updateConstants();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateConstants();
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
                            Slog.e(TAG, "Load profile for packageName=" + profile.mPackageName + ", uid=" + uid);
                            _profiles.put(uid, profile);
                        } else {
                            Slog.e(TAG, "Duplicated profile for uid=" + uid + ", packageName=" + profile.mPackageName);
                        }
                    }
                }
            }

    
        } catch (Exception e) {
            Slog.e(TAG, "Bad BaikalService settings", e);
            return;
        }

        _staticProfilesByPackgeName =  _profilesByPackgeName;
        _staticProfiles = _profiles;
    }
    
    public void saveLocked() {
        String val = "";

//        for(AppProfile profile: _profiles) {

        for(Map.Entry<String, AppProfile> entry : _profilesByPackgeName.entrySet()) {
            if( entry.getValue().isDefault() ) { 
                Slog.e(TAG, "Skip saving default profile for packageName=" + entry.getValue().mPackageName);
                continue;
            }
            Slog.e(TAG, "Save profile for packageName=" + entry.getValue().mPackageName);
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
        //return _profiles.get(packageName);
	    return _profilesByPackgeName.get(packageName);
    }

    public AppProfile getProfileLocked(int uid, String packageName) {
        //return _profiles.get(packageName);
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
}

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

import android.bluetooth.BluetoothDevice;

import android.util.Slog;

import android.text.TextUtils;
import android.util.KeyValueListParser;

import android.os.UserHandle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;

import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.ActivityThread;

import android.net.Uri;

import android.database.ContentObserver;

import android.provider.Settings;

import java.util.HashMap;

public class BaikalSettings extends ContentObserver {

    private static final String TAG = "Baikal.Settings";

    private static ContentResolver mResolver;
    private static Handler mHandler;
    private static Context mContext;
   	private static Object _staticLock = new Object();

    private static long mDebug = -1;

   	private static boolean mHallSensorEnabled;
   	private static boolean mProximityWakeEnabled;
   	private static boolean mProximitySleepEnabled;
   	private static boolean mTorchInCallEnabled;
   	private static boolean mTorchNotificationEnabled;
   	private static boolean mAggressiveIdleEnabled;
   	private static boolean mExtremeIdleEnabled;
   	private static boolean mHideGmsEnabled;
   	private static boolean mUnrestrictedNetEnabled;
    private static boolean mStaminaMode;
    private static boolean mStaminaOiMode;
    private static boolean mDisableHeadphonesDetect;

    private static boolean mIsGmsBlocked;
    private static boolean mIsGmsRestricted;
    private static boolean mIsGmsRestrictedIdle;
    private static boolean mIsGmsRestrictedStamina;
    private static boolean mIsGpsRestricted;

    private static int mDefaultRotation;

    private static String mFilterServices;
    private static String mFilterAlarms;
    private static String mFfilterBcast;
    private static String mFilterActivity;

    private static String mFilterServicesIdle;
    private static String mFilterAlarmsIdle;
    private static String mFilterBcastIdle;
    private static String mFilterActivityIdle;

    private static String mAlarmsNoWake;

    private static String mSbcBitrateString;

    private static boolean mKeepOn;

    private static int mTopAppUid;
    private static String mTopAppPackageName;

    private static boolean mScreenOn = true;

    private static boolean mReaderMode;

    private static boolean mIdleMode;

    public static boolean getKeepOn() {
        return mKeepOn;
    }

    public static void setKeepOn(boolean keepOn) {
        mKeepOn = keepOn;
    }

    public static boolean getScreenOn() {
        return mScreenOn;
    }

    static void setScreenOn(boolean mode) {
        mScreenOn = mode;
    }

    public static boolean getReaderMode() {
        return mReaderMode;
    }

    static void setReaderMode(boolean mode) {
        mReaderMode = mode;
    }

   	public static boolean getHallSensorEnabled() {
   	    return mHallSensorEnabled;
   	}

   	public static boolean getProximityWakeEnabled() {
   	    return mProximityWakeEnabled;
   	}

   	public static boolean getProximitySleepEnabled() {
   	    return mProximitySleepEnabled;
   	} 

   	public static boolean getTorchInCallEnabled() {
   	    return mTorchInCallEnabled;
   	}

	public static boolean getTorchNotificationEnabled() {
	    return mTorchNotificationEnabled;
	}

	public static boolean getAggressiveIdleEnabled() {
	    return mAggressiveIdleEnabled || mExtremeIdleEnabled;
	}

	public static boolean getExtremeIdleEnabled() {
	    return mExtremeIdleEnabled;
	}

	public static boolean getExtremeIdleActive() {
	    return mExtremeIdleEnabled && Runtime.isIdleMode();
	}
	
	public static boolean getHideGmsEnabled() {
	    return mHideGmsEnabled;
	}

	public static boolean getUnrestrictedNetEnabled() {
	    return mUnrestrictedNetEnabled;
	}

	public static int getDefaultRotation() {
	    return mDefaultRotation;
	}

	public static boolean getStaminaMode() {

        if( mStaminaMode || (mStaminaOiMode && Runtime.isIdleMode()) ) {
            return true;
        }
        return false;
	}

	public static boolean getDisableHeadphonesDetect() {
	    return mDisableHeadphonesDetect;
	}

    public static boolean getAppRestricted(int uid) {
        if( uid < Process.FIRST_APPLICATION_UID ) return false;
        return getAppRestricted(uid,null);
    }

    public static boolean getAppRestricted(int uid, String packageName) {

        if( !getAggressiveIdleEnabled() ) return false;

        if( uid < Process.FIRST_APPLICATION_UID && packageName == null ) return false;

        boolean ret = getAppRestrictedInternal(uid,packageName);

        if( ret && BaikalConstants.BAIKAL_DEBUG_RAW ) {
            Slog.e(TAG, "getAppRestricted: ret=" + ret + ", uid=" + uid + ", pkg=" + packageName + ", top=" + mTopAppUid );
        }
        return ret;
    }

    public static boolean getAppBlocked(int uid, String packageName) {

        if( !getAggressiveIdleEnabled() ) return false;

        if( uid < Process.FIRST_APPLICATION_UID && packageName == null ) return false;

        boolean ret = getAppBlockedInternal(uid,packageName);

        if( ret && BaikalConstants.BAIKAL_DEBUG_RAW ) {
            Slog.e(TAG, "getAppBlocked: ret=" + ret + ", uid=" + uid + ", pkg=" + packageName + ", top=" + mTopAppUid );
        }
        return ret;
    }
                          
    public static boolean getAppBlockedInternal(int uid, String packageName) {

        if( Runtime.isGmsUid(uid) ) {
            AppProfile profile = AppProfileManager.getCurrentProfile();
            if( profile != null && profile.mRequireGms ) return false;
            if( packageName != null && packageName.equals("com.google.android.gms") ) {
                return isGmsBlocked();
            }
        }

        if( uid < Process.FIRST_APPLICATION_UID && packageName == null ) return false;

        if( uid < Process.FIRST_APPLICATION_UID && packageName != null ) {
            if( packageName.equals(mTopAppPackageName) ) return false;
        }
        else if(  uid == mTopAppUid ) { 
            return false;
        }

        AppProfile profile = null; 
        if( packageName == null ) profile = AppProfileSettings.getProfileStatic(uid);
        else profile = AppProfileSettings.getProfileStatic(packageName);
        if( profile == null ) {
            if(!getStaminaMode()) return false;
        }

        if( getStaminaMode() )  {
            if( uid < Process.FIRST_APPLICATION_UID )  return false;
            if( profile != null && profile.mStamina ) return false;
            if( packageName == null ) return true;
            if( packageName.startsWith("com.android.service.ims") ) return false;
            if( packageName.startsWith("com.android.launcher3") ) return false;
            if( packageName.startsWith("com.android.systemui") ) return false;
            if( packageName.startsWith("com.android.nfc") ) return false;
            if( packageName.startsWith("com.android.providers") ) return false;
            if( packageName.startsWith("com.android.inputmethod") ) return false;
            if( packageName.startsWith("com.qualcomm.qti.telephonyservice") ) return false;
            if( packageName.startsWith("com.android.phone") ) return false;
            if( packageName.startsWith("com.android.server.telecom") ) return false;
            return true;
        }

        if( profile != null ) {
            if( profile.mBackground > 2 ) return true;
            if( profile.mBackground > 1 && Runtime.isIdleMode() ) return true;
        }

        return false;
    }


    public static boolean getAppRestrictedInternal(int uid, String packageName) {
        if( Runtime.isGmsUid(uid) ) {
            AppProfile profile = AppProfileManager.getCurrentProfile();
            if( profile != null && profile.mRequireGms ) return false;
            if( packageName != null && packageName.equals("com.google.android.gms") ) {
                return isGmsRestricted();
            }
        }

        if( uid < Process.FIRST_APPLICATION_UID && packageName == null ) return false;

        if( uid < Process.FIRST_APPLICATION_UID ) {
            if( packageName.equals(mTopAppPackageName) ) return false;
        }
        else if(  uid == mTopAppUid ) { 
            return false;
        }

        AppProfile profile = null; 
        if( packageName == null ) profile = AppProfileSettings.getProfileStatic(uid);
        else profile = AppProfileSettings.getProfileStatic(packageName);
        if( profile == null ) {
            if(!getStaminaMode()) return false;
        }

        if( getStaminaMode() )  {
            if( uid < Process.FIRST_APPLICATION_UID )  return false;
            if( profile != null && (profile.mStamina || profile.mBackground < 0) ) return false;
            if( packageName == null ) return true;
            if( packageName.startsWith("com.android.service.ims") ) return false;
            if( packageName.startsWith("com.android.launcher3") ) return false;
            if( packageName.startsWith("com.android.systemui") ) return false;
            if( packageName.startsWith("com.android.nfc") ) return false;
            if( packageName.startsWith("com.android.providers") ) return false;
            if( packageName.startsWith("com.android.inputmethod") ) return false;
            if( packageName.startsWith("com.qualcomm.qti.telephonyservice") ) return false;
            if( packageName.startsWith("com.android.phone") ) return false;
            if( packageName.startsWith("com.android.server.telecom") ) return false;
            return true;
        }


        if( profile.mBackground > 1 ) return true;
        if( profile.mBackground > 0 && Runtime.isIdleMode() ) return true;

        return false;
    }

    public static boolean isGmsRestricted() {
        AppProfile profile = AppProfileManager.getCurrentProfile();
        if( profile != null && profile.mRequireGms ) return false;

        //if( mIsGmsRestricted ) return true;
        //if( mIsGmsRestrictedIdle && Runtime.isIdleMode() ) return true;
        if( mIsGmsBlocked ) return true;
        if( mIsGmsRestrictedStamina && getStaminaMode() ) return true;
        return false;
    }

    public static boolean isGmsBlocked() {

        AppProfile profile = AppProfileManager.getCurrentProfile();
        if( profile != null && profile.mRequireGms ) return false;

        if( mIsGmsBlocked ) return true;
        if( mIsGmsRestrictedStamina && getStaminaMode() ) return true;
        return false;
    }


    public static boolean isGpsRestricted() {
        if( mIsGpsRestricted ) return true;
        if( mIsGmsRestrictedIdle && Runtime.isIdleMode() ) return true;
        if( mIsGmsRestrictedStamina && getStaminaMode() ) return true;
        return false;
    }

    public static AppProfile getCurrentProfile() {
        return AppProfileManager.getCurrentProfile();
    }

    public static void setTopApp(int uid, String packageName) {
        mTopAppUid = uid;
        synchronized(_staticLock) {
            mTopAppPackageName = packageName;
        }
    }

    public static int getTopAppUid() {
        return mTopAppUid;
    }

    public static String getTopAppPackageName() {
        synchronized(_staticLock) {
            return mTopAppPackageName;
        }
    }

    public BaikalSettings(Handler handler, Context context) {
        super(handler);
	    mHandler = handler;
        mContext = context;
        mResolver = context.getContentResolver();

        try {
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_HALL_SENSOR),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_PROXIMITY_WAKE_SENSOR),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_PROXIMITY_SLEEP_SENSOR),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_TORCH_INCALL),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_AGGRESSIVE_IDLE),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_EXTREME_IDLE),
                    false, this);


                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_TORCH_NOTIFICATION),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_HIDE_GMS),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_UNRESTRICTED_NET),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_STAMINA_ENABLED),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_DISABLE_HP_DETECT),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_STAMINA_OI_ENABLED),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_GMS_RESTRICTED),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_GPS_RESTRICTED),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_GMS_IDLE_RESTRICTED),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_GMS_STAMINA_RESTRICTED),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_GMS_BLOCKED),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_SERVICES),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_ALARMS),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_BCAST),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_ACTIVITY),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_SERVICES_IDLE),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_ALARMS_IDLE),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_BCAST_IDLE),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_FILTER_ACTIVITY_IDLE),
                    false, this);
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_ALARMS_NOWAKE),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_DEBUG),
                    false, this);

                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_DEFAULT_ROTATION),
                    false, this);

            } catch( Exception e ) {
            }

            updateConstants(true);
            loadStaticConstants(mContext);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        loadStaticConstants(mContext);
        updateConstants(false);
    }

    private void updateConstants(boolean startup) {
        synchronized (_staticLock) {
            updateConstantsLocked(startup);
        }
    }

    public static void loadStaticConstants(Context context) {
        synchronized (_staticLock) {
            try {

                mIsGmsBlocked = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BAIKALOS_GMS_BLOCKED,0) == 1;

                mIsGmsRestricted = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BAIKALOS_GMS_RESTRICTED,0) == 1;

                mIsGpsRestricted = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BAIKALOS_GPS_RESTRICTED,0) == 1;

                mIsGmsRestrictedIdle = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BAIKALOS_GMS_IDLE_RESTRICTED,0) == 1;

                mIsGmsRestrictedStamina = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BAIKALOS_GMS_STAMINA_RESTRICTED,0) == 1;

                mFilterServices = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_SERVICES);
                mFilterAlarms = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_ALARMS);
                mFfilterBcast = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_BCAST);
                mFilterActivity = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_ACTIVITY);
                mFilterServicesIdle = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_SERVICES_IDLE);
                mFilterAlarmsIdle = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_ALARMS_IDLE);
                mFilterBcastIdle = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_BCAST_IDLE);
                mFilterActivityIdle = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_FILTER_ACTIVITY_IDLE);
                mAlarmsNoWake = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_ALARMS_NOWAKE);

                long debug = Settings.Global.getLong(context.getContentResolver(),
                        Settings.Global.BAIKALOS_DEBUG,0);

                mDefaultRotation = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BAIKALOS_DEFAULT_ROTATION,0);
            
                if( debug != mDebug ) {
                    mDebug = debug;
                    updateDebug();
                }

                updateStaticConstantsLocked();

            } catch (Exception e) {
                Slog.e(TAG, "Bad BaikalService settings ", e);
            }
        }
    }

    private static void updateStaticConstantsLocked() {
        //updateFilters();
        
    }

    public void updateConstantsLocked(boolean startup) {
        try {

            boolean hallSensorEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_HALL_SENSOR,0) == 1;

	        if( hallSensorEnabled != mHallSensorEnabled ) hallSensorEnabledChanged(hallSensorEnabled);

            boolean proximityWakeEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_PROXIMITY_WAKE_SENSOR,0) == 1;

            boolean proximitySleepEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_PROXIMITY_SLEEP_SENSOR,0) == 1;

            if( proximityWakeEnabled != mProximityWakeEnabled ||
                proximitySleepEnabled != mProximitySleepEnabled ) proximityEnabledChanged(proximityWakeEnabled,proximitySleepEnabled);

            boolean torchInCallEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_TORCH_INCALL,0) == 1;

            if( torchInCallEnabled != mTorchInCallEnabled ) torchInCallEnabledChanged(torchInCallEnabled);

            boolean aggressiveIdleEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_AGGRESSIVE_IDLE,0) == 1;

            boolean extremeIdleEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_EXTREME_IDLE,0) == 1;

            if( aggressiveIdleEnabled != mAggressiveIdleEnabled ||
		        extremeIdleEnabled != mExtremeIdleEnabled ) idleModeEnabledChanged(aggressiveIdleEnabled,extremeIdleEnabled);

            boolean torchNotificationEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_TORCH_NOTIFICATION,0) == 1;

            if( torchNotificationEnabled != mTorchNotificationEnabled ) torchNotificationEnabledChanged(torchNotificationEnabled);

            boolean hideGmsEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_HIDE_GMS,0) == 1;

            if( hideGmsEnabled != mHideGmsEnabled ) hideGmsEnabledChanged(hideGmsEnabled);

            boolean unrestrictedNetEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_UNRESTRICTED_NET,0) == 1;

            if( unrestrictedNetEnabled != mUnrestrictedNetEnabled ) unrestrictedNetEnabledChanged(unrestrictedNetEnabled);

            if( startup ) {
                Settings.Global.putInt(mResolver,
                    Settings.Global.BAIKALOS_STAMINA_ENABLED,0);
                mStaminaMode = false;
            } else {
                boolean staminaMode = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_STAMINA_ENABLED,0) == 1;
                if( staminaMode != mStaminaMode ) staminaModeChanged(staminaMode);
            }

            mDisableHeadphonesDetect = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_DISABLE_HP_DETECT,0) == 1;

            mStaminaOiMode = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_STAMINA_OI_ENABLED,0) == 1;

            mIsGmsRestricted = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_GMS_RESTRICTED,0) == 1;

            mIsGpsRestricted = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_GPS_RESTRICTED,0) == 1;

            mIsGmsRestrictedIdle = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_GMS_IDLE_RESTRICTED,0) == 1;

            mIsGmsRestrictedStamina = Settings.Global.getInt(mResolver,
                    Settings.Global.BAIKALOS_GMS_STAMINA_RESTRICTED,0) == 1;

            mHandler.removeMessages(Messages.MESSAGE_SETTINGS_UPDATE);
            Message msg = mHandler.obtainMessage(Messages.MESSAGE_SETTINGS_UPDATE);
            mHandler.sendMessage(msg);

        } catch (Exception e) {
            Slog.e(TAG, "Bad BaikalService settings ", e);
        }
    }

    void hallSensorEnabledChanged(boolean hall) {
	    mHallSensorEnabled = hall;
	}

	void proximityEnabledChanged(boolean wake, boolean sleep) {
	    mProximityWakeEnabled = wake;
	    mProximitySleepEnabled = sleep;
	}
	void torchInCallEnabledChanged(boolean enabled) {
	    mTorchInCallEnabled = enabled;
	}

	void torchNotificationEnabledChanged(boolean enabled) {
	    mTorchNotificationEnabled = enabled;
	}

	void hideGmsEnabledChanged(boolean enabled) {
	    mHideGmsEnabled = enabled;
	}

	void unrestrictedNetEnabledChanged(boolean enabled) {
	    mUnrestrictedNetEnabled = enabled;
	}

	void staminaModeChanged(boolean enabled) {
	    mStaminaMode = enabled;
        
        Slog.e(TAG, "Stamina mode changed: " + mStaminaMode);

        Actions.sendStaminaChanged(enabled);
	}

	void idleModeEnabledChanged(boolean aggressive, boolean extreme) {

	    if( mExtremeIdleEnabled != extreme ) {

	        mExtremeIdleEnabled = extreme;

            if( mExtremeIdleEnabled ) {
                Settings.Global.putInt(mResolver,
	    	        Settings.Global.FORCED_APP_STANDBY_ENABLED, mExtremeIdleEnabled ? 1:0);
            } else if( !mAggressiveIdleEnabled ) {
                Settings.Global.putInt(mResolver,
	    	        Settings.Global.FORCED_APP_STANDBY_ENABLED, mAggressiveIdleEnabled ? 1:0);
            }
            Settings.Global.putInt(mResolver,
                Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED, mExtremeIdleEnabled ? 1:0);

	    }

        
        if( mAggressiveIdleEnabled != aggressive ) {

	        mAggressiveIdleEnabled = aggressive;

            Settings.Global.putInt(mResolver,
		        Settings.Global.FORCED_APP_STANDBY_ENABLED, mAggressiveIdleEnabled ? 1:0);
        }

	}

    public static void setIdleMode(boolean mode) {
        if( mIdleMode != mode ) {
            mIdleMode = mode;

            if( mAggressiveIdleEnabled ) {
                Settings.Global.putInt(mResolver,
    		        Settings.Global.FORCED_APP_STANDBY_ENABLED, mIdleMode ? 1:0);
            }

    	    if( mExtremeIdleEnabled ) {
                Settings.Global.putInt(mResolver,
                    Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED, mIdleMode ? 1:0);
            }
        }
    }


    private static Object mBtDatabaseLock = new Object();

    private static HashMap<String, Integer> mBtDatabase = null; 

    private static final TextUtils.StringSplitter mBtDbSplitter = new TextUtils.SimpleStringSplitter('|');


    public static boolean setSbcBitrate(Context context, BluetoothDevice device, int value) {
        synchronized(mBtDatabaseLock) {
            if( mBtDatabase == null ) {
                updateSbcBitratesLocked(context);
            }
            Integer prev = mBtDatabase.get(device.getAddress());
            if( prev != null &&  prev.equals(Integer.valueOf(value)) ) return false;
            Slog.i(TAG, "a2dp: SBC set bitrate: device=" + device + ", rate=" + value);
            mBtDatabase.put(device.getAddress(),Integer.valueOf(value));
            saveSbcBitratesLocked(context);
        }
        return true;
    }

    public static int getSbcBitrate(Context context, BluetoothDevice device) {
        synchronized(mBtDatabaseLock) {
            if( mBtDatabase == null ) {
                updateSbcBitratesLocked(context);
            }
            if( !mBtDatabase.containsKey(device.getAddress()) ) {
                Slog.i(TAG, "a2dp: SBC bitrate is not set: device=" + device);
                return 0;
            }
            int rate = mBtDatabase.get(device.getAddress()).intValue();
            Slog.i(TAG, "a2dp: SBC bitrate:device=" + device + ", rate="  + rate);
            return rate;
        }
    }

    private static void updateSbcBitratesLocked(Context context) {
        //synchronized(mBtDatabaseLock) {

            mBtDatabase = new HashMap<String,Integer> ();
            //mBtDatabase.clear();

            String sbcBitrateString = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_SBC_BITRATE);

            if( sbcBitrateString == null ) return;
            if( sbcBitrateString.equals(mSbcBitrateString) ) return;

            mSbcBitrateString = sbcBitrateString;

            try {
                mBtDbSplitter.setString(mSbcBitrateString);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad mSbcBitrateString settings :" + mSbcBitrateString, e);
                return ;
            }

            for(String deviceString:mBtDbSplitter) {

                KeyValueListParser parser = new KeyValueListParser(',');

                try {
                    parser.setString(deviceString);
                    String address = parser.getString("addr",null);
                    if( address == null || address.equals("") ) continue;
                    int bitrate = parser.getInt("sbcbr",0);
                    mBtDatabase.put(address,bitrate);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Bad deviceString :" + deviceString, e);
                    continue;
                }
            }
        //}        
    }

    private static void saveSbcBitratesLocked(Context context) {
        synchronized(mBtDatabaseLock) {
            String val = "";    

            for(HashMap.Entry<String, Integer> entry : mBtDatabase.entrySet()) {
                String entryString = "addr=" + entry.getKey().toString() + "," + "sbcbr=" +  entry.getValue().toString();
                if( entryString != null ) val += entryString + "|";
            } 

            if( mSbcBitrateString != null && mSbcBitrateString.equals(val) ) return;
            mSbcBitrateString = val;
            Settings.Global.putString(context.getContentResolver(),Settings.Global.BAIKALOS_SBC_BITRATE,val);
        }
    }



    private static Object mAudioDatabaseLock = new Object();

    private static HashMap<Integer, AudioEntry> mAudioDatabase = null; 

    private static final TextUtils.StringSplitter mAudioDbSplitter = new TextUtils.SimpleStringSplitter('|');

    private static String mAudioString;

    private static Context getContext() {
        ActivityThread at = ActivityThread.currentActivityThread();
        if( at == null ) return null;
        return at.getApplication();
    }


    public static boolean getBlockFocusRecv(int uid) {
        synchronized(mAudioDatabaseLock) {
            updateAudioDatabaseLocked(getContext());

            if( !mAudioDatabase.containsKey(uid) ) {
                Slog.i(TAG, "audio: block focus recv is not set: uid=" + uid);
                return false;
            }
            AudioEntry ad = mAudioDatabase.get(uid);
            Slog.i(TAG, "audio: block focus recv:uid=" + ad.mUid + ", rc="  + ad.mBlockAfRecv);
            return ad.mBlockAfRecv;
        }
    }

    public static boolean getBlockFocusSend(int uid) {
        synchronized(mAudioDatabaseLock) {
            updateAudioDatabaseLocked(getContext());

            if( !mAudioDatabase.containsKey(uid) ) {
                Slog.i(TAG, "audio: block focus send is not set: uid=" + uid);
                return false;
            }
            AudioEntry ad = mAudioDatabase.get(uid);
            Slog.i(TAG, "audio: block focus send:uid=" + ad.mUid + ", rc="  + ad.mBlockAfSend);
            return ad.mBlockAfSend;
        }
    }

    public static boolean setBlockFocusRecv(int uid, boolean value) {
        synchronized(mAudioDatabaseLock) {
            if( mAudioDatabase == null ) {
                updateAudioDatabaseLocked(getContext());
            }
            AudioEntry prev = mAudioDatabase.get(uid);
            if( prev != null && prev.mBlockAfRecv == value ) return false;
            Slog.i(TAG, "audio: block focus recv: uid=" + uid + ", rc=" + value);
            if( prev == null ) {
                prev = new AudioEntry();    
                prev.mUid = uid;
                mAudioDatabase.put(uid,prev);
            }
            prev.mBlockAfRecv = value;
            saveVolumeDatabaseLocked(getContext());
        }
        return true;
    }

    public static boolean setBlockFocusSend(int uid, boolean value) {
        synchronized(mAudioDatabaseLock) {
            if( mAudioDatabase == null ) {
                updateAudioDatabaseLocked(getContext());
            }
            AudioEntry prev = mAudioDatabase.get(uid);
            if( prev != null && prev.mBlockAfSend == value ) return false;
            Slog.i(TAG, "audio: block focus send: uid=" + uid + ", rc=" + value);
            if( prev == null ) {
                prev = new AudioEntry();    
                prev.mUid = uid;
                mAudioDatabase.put(uid,prev);
            }
            prev.mBlockAfSend = value;
            saveVolumeDatabaseLocked(getContext());
        }
        return true;
    }


    public static boolean getForceSonification(int uid) {
        synchronized(mAudioDatabaseLock) {
            updateAudioDatabaseLocked(getContext());

            if( !mAudioDatabase.containsKey(uid) ) {
                Slog.i(TAG, "audio: force sonification is not set: uid=" + uid);
                return false;
            }
            AudioEntry ad = mAudioDatabase.get(uid);
            Slog.i(TAG, "audio: force sonification :uid=" + ad.mUid + ", rc="  + ad.mForceSonification);
            return ad.mForceSonification;
        }
    }

    public static boolean setForceSonification(int uid, boolean value) {
        synchronized(mAudioDatabaseLock) {
            if( mAudioDatabase == null ) {
                updateAudioDatabaseLocked(getContext());
            }
            AudioEntry prev = mAudioDatabase.get(uid);
            if( prev != null && prev.mForceSonification == value ) return false;
            Slog.i(TAG, "audio: force notification: uid=" + uid + ", rc=" + value);
            if( prev == null ) {
                prev = new AudioEntry();    
                prev.mUid = uid;
                mAudioDatabase.put(uid,prev);
            }
            prev.mForceSonification = value;
            saveVolumeDatabaseLocked(getContext());
        }
        return true;
    }


    public static boolean setVolumeScaleInt(int uid, int value) {
        synchronized(mAudioDatabaseLock) {
            if( mAudioDatabase == null ) {
                updateAudioDatabaseLocked(getContext());
            }
            AudioEntry prev = mAudioDatabase.get(uid);
            if( prev != null && prev.mVolumeScale == value ) return false;
            Slog.i(TAG, "audio: set volume scale: uid=" + uid + ", rate=" + value);
            if( prev == null ) {
                prev = new AudioEntry();    
                prev.mUid = uid;
                mAudioDatabase.put(uid,prev);
            }
            prev.mVolumeScale = value;
            saveVolumeDatabaseLocked(getContext());
        }
        return true;
    }

    public static int getVolumeScaleInt(int uid) {
        synchronized(mAudioDatabaseLock) {
            if( mAudioDatabase == null ) {
                updateAudioDatabaseLocked(getContext());
            }
            if( !mAudioDatabase.containsKey(uid) ) {
                Slog.i(TAG, "audio: volume scale is not set: uid=" + uid);
                return -1;
            }
            AudioEntry ad = mAudioDatabase.get(uid);
            if( ad.mVolumeScale == -1 ) {
                Slog.i(TAG, "audio: volume scale is default: uid=" + uid);
                return -1;
            }
            if( ad.mVolumeScale > 100 ) {
                Slog.i(TAG, "audio: volume scale > 100. fix it: uid=" + uid);
                ad.mVolumeScale = 100;
                return -1;
            }
            Slog.i(TAG, "audio: volume scale:uid=" + ad.mUid + ", scale="  + ad.mVolumeScale);
            return ad.mVolumeScale;
        }
    }

    public static float getVolumeScale(int uid) {
        synchronized(mAudioDatabaseLock) {
            updateAudioDatabaseLocked(getContext());
        }

        int volScale = getVolumeScaleInt(uid);
        if( volScale == -1 ) {
                return 100.0F;
        }
        return ((float)volScale);
    }

    private static void updateAudioDatabaseLocked(Context context) {
        try {
            if( context == null ) return;

            mAudioDatabase = new HashMap<Integer,AudioEntry> ();

            String audioString = Settings.Global.getString(context.getContentResolver(),
                        Settings.Global.BAIKALOS_AUIDO_PROFILE);

            if( audioString == null ) return;
            //if( audioString.equals(mAudioString) ) return;

            mAudioString = audioString;

            try {
                mAudioDbSplitter.setString(mAudioString);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad mAudioString settings :" + mAudioString, e);
                return ;
            }

            for(String appString:mAudioDbSplitter) {

                KeyValueListParser parser = new KeyValueListParser(',');

                try {
                    AudioEntry entry = AudioEntry.Deserialize(appString);
                    Slog.e(TAG, "Loaded :" + entry.toString());
                    mAudioDatabase.put(entry.mUid,entry);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Bad appString :" + appString, e);
                    continue;
                }
            }

        } catch(Exception e) {
            Slog.e(TAG, "audio: can't load audio db", e);
        }

    }

    private static void saveVolumeDatabaseLocked(Context context) {
        if( context == null ) return;
        try {
            String val = "";    
            for(HashMap.Entry<Integer, AudioEntry> entry : mAudioDatabase.entrySet()) {
                String entryString = entry.getValue().Serialize();
                if( entryString != null ) val += entryString + "|";
            } 

            if( mAudioString != null && mAudioString.equals(val) ) return;
            mAudioString = val;
            Settings.Global.putString(context.getContentResolver(),Settings.Global.BAIKALOS_AUIDO_PROFILE,val);
        } catch(Exception e) {
            Slog.e(TAG, "audio: can't save audio db", e);
        }
    }

    private static void updateDebug() {

        BaikalConstants.BAIKAL_DEBUG_TEMPLATE = false;
        BaikalConstants.BAIKAL_DEBUG_SENSORS = false;
        BaikalConstants.BAIKAL_DEBUG_TORCH = false;
        BaikalConstants.BAIKAL_DEBUG_TELEPHONY = false;
        BaikalConstants.BAIKAL_DEBUG_TELEPHONY_RAW = false;
        BaikalConstants.BAIKAL_DEBUG_BLUETOOTH = false;
        BaikalConstants.BAIKAL_DEBUG_ACTIONS = false;
        BaikalConstants.BAIKAL_DEBUG_APP_PROFILE = false;
        BaikalConstants.BAIKAL_DEBUG_DEV_PROFILE = false;
        BaikalConstants.BAIKAL_DEBUG_SERVICES = false;
        BaikalConstants.BAIKAL_DEBUG_ACTIVITY = false;
        BaikalConstants.BAIKAL_DEBUG_ALARM = false;
        BaikalConstants.BAIKAL_DEBUG_BROADCAST = false;

        if( (mDebug&BaikalConstants.DEBUG_MASK_ALL) != 0 ) mDebug =0xFFFFFF; 
        if( (mDebug&BaikalConstants.DEBUG_MASK_TEMPLATE) !=0 ) BaikalConstants.BAIKAL_DEBUG_TEMPLATE = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_SENSORS) !=0 ) BaikalConstants.BAIKAL_DEBUG_SENSORS = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_TORCH) !=0 ) BaikalConstants.BAIKAL_DEBUG_TORCH = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_TELEPHONY) !=0 ) BaikalConstants.BAIKAL_DEBUG_TELEPHONY = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_TELEPHONY_RAW) !=0 ) BaikalConstants.BAIKAL_DEBUG_TELEPHONY_RAW = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_BLUETOOTH) !=0 ) BaikalConstants.BAIKAL_DEBUG_BLUETOOTH = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_ACTIONS) !=0 ) BaikalConstants.BAIKAL_DEBUG_ACTIONS = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_APP_PROFILE) !=0 ) BaikalConstants.BAIKAL_DEBUG_APP_PROFILE = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_DEV_PROFILE) !=0 ) BaikalConstants.BAIKAL_DEBUG_DEV_PROFILE = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_SERVICES) !=0 ) BaikalConstants.BAIKAL_DEBUG_SERVICES = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_ACTIVITY) !=0 ) BaikalConstants.BAIKAL_DEBUG_ACTIVITY = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_ALARM) !=0 ) BaikalConstants.BAIKAL_DEBUG_ALARM = true;
        if( (mDebug&BaikalConstants.DEBUG_MASK_BROADCAST) !=0 ) BaikalConstants.BAIKAL_DEBUG_BROADCAST = true;
    }
}


    class AudioEntry {

        private static final String TAG = "Baikal.Settings";

        public int mUid;
        public int mVolumeScale;
        public boolean mBlockAfRecv;
        public boolean mBlockAfSend;
        public boolean mConvertAfRecv;
        public boolean mConvertAfSend;
        public boolean mForceSonification;

        public AudioEntry() {
            mUid=-1;
            mVolumeScale=-1;
            mBlockAfRecv=false;
            mBlockAfSend=false;
            mConvertAfRecv=false;
            mConvertAfSend=false;
            mForceSonification=false;
        }

        public String Serialize() {
            String entryString = "";
            if( mVolumeScale !=-1 ) entryString += "," + "vol="  + mVolumeScale;
            if( mBlockAfRecv ) entryString += "," + "bafr=" + mBlockAfRecv;
            if( mBlockAfSend ) entryString += "," + "bafs=" + mBlockAfSend;
            if( mConvertAfRecv ) entryString += "," + "cafr=" + mConvertAfRecv;
            if( mConvertAfSend ) entryString += "," + "cafs=" + mConvertAfSend;
            if( mForceSonification ) entryString += "," + "fsn=" + mForceSonification;

            if( !entryString.equals("") ) {
                entryString = "uid=" + mUid + entryString;
                return entryString;
            }
            return null;
        }

        public static AudioEntry Deserialize(String val) {

            KeyValueListParser parser = new KeyValueListParser(',');

            AudioEntry profile = new AudioEntry();
            try {
                parser.setString(val);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad profile settings", e);
                return null;
            }
            try {
                profile.mUid = parser.getInt("uid",-1);
                if( profile.mUid == -1 ) return null;
                profile.mVolumeScale = parser.getInt("vol",-1);
                profile.mBlockAfRecv = parser.getBoolean("bafr",false);
                profile.mBlockAfSend = parser.getBoolean("bafs",false);
                profile.mConvertAfRecv = parser.getBoolean("cafr",false);
                profile.mConvertAfSend = parser.getBoolean("cafs",false);
                profile.mForceSonification = parser.getBoolean("fsn",false);
            } catch( Exception e ) {
    
            }
            return profile;
        }
        @Override
        public String toString() {
            return "{" + Serialize() + "}";
        }
    }

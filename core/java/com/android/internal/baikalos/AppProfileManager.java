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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Process;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.CellLocation;
import android.telephony.CellInfo;
import android.telephony.SignalStrength;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.VoLteServiceState;

import android.os.AsyncTask;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

import android.provider.Settings;

import com.android.internal.view.RotationPolicy;
import android.view.WindowManagerGlobal;
import android.view.IWindowManager;

public class AppProfileManager extends MessageHandler { 

    private static final String TAG = "Baikal.AppProfile";

    private static Object mLock = new Object();

    private AppProfileSettings mAppSettings;

    private boolean mOnCharger=false;
    private boolean mDeviceIdleMode = false;
    private boolean mScreenMode = true;
    private int mTopUid=-1;
    private String mTopPackageName;
    private boolean mReaderMode;
    private boolean mPhoneCall;

    private boolean mIdleProfileActive;
    private String mActivePerfProfile = "";
    private String mActiveThermProfile = "";

    private String mScreenOffPerfProfile = "screen_off";
    private String mScreenOffThermProfile = "screen_off";

    private String mIdlePerfProfile = "idle";
    private String mIdleThermProfile = "idle";

    private static Object mCurrentProfileSync = new Object();
    private static AppProfile mCurrentProfile = null;

    private int mActiveFrameRate=-2;
    private int mDefaultFps = -10;

    private boolean mReaderModeAvailable = false;
    private boolean mVariableFps = false;

    TelephonyManager mTelephonyManager;

    public static AppProfile getCurrentProfile() {
        synchronized(mCurrentProfileSync) {
            return mCurrentProfile;
        }
    }

    @Override
    protected void initialize() {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"initialize()");                
        synchronized(mLock) {

            mAppSettings = AppProfileSettings.getInstance(getHandler(), getContext(), getContext().getContentResolver(), null);

            IntentFilter topAppFilter = new IntentFilter();
            topAppFilter.addAction(Actions.ACTION_TOP_APP_CHANGED);
            getContext().registerReceiver(mTopAppReceiver, topAppFilter);

            IntentFilter idleFilter = new IntentFilter();
            idleFilter.addAction(Actions.ACTION_IDLE_MODE_CHANGED);
            getContext().registerReceiver(mIdleReceiver, idleFilter);

            IntentFilter chargerFilter = new IntentFilter();
            chargerFilter.addAction(Actions.ACTION_CHARGER_MODE_CHANGED);
            getContext().registerReceiver(mChargerReceiver, chargerFilter);

            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Actions.ACTION_SCREEN_MODE_CHANGED);
            getContext().registerReceiver(mScreenReceiver, screenFilter);

            IntentFilter profileFilter = new IntentFilter();
            profileFilter.addAction(Actions.ACTION_SET_PROFILE);
            getContext().registerReceiver(mProfileReceiver, profileFilter);

            mTelephonyManager = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            mTelephonyManager.listen(mPhoneStateListener, 0xFFFFFFF);

            mReaderModeAvailable  = SystemProperties.get("sys.baikal.reader", "1").equals("1");
            mVariableFps = SystemProperties.get("sys.baikal.var_fps", "0").equals("1");
        }
    }


    @Override
    public boolean onMessage(Message msg) {
        return false;
    }

    protected void setActivePerfProfileLocked(String profile) {
        if( mActivePerfProfile != profile ) {
            mActivePerfProfile = profile;
            setHwPerfProfileLocked(profile, false);
        }
    }

    protected void setActiveThermProfileLocked(String profile) {
        if( mActiveThermProfile != profile ) {
            mActiveThermProfile = profile;
            setHwThermProfileLocked(profile, false);
        }
    }

    protected void setActiveFrameRateLocked(int fps) {

        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"setActiveFrameRateLocked :" + fps);

        int setfps = fps;
        if( fps == -1 ) {
            try {
                int value = Integer.parseInt(SystemProperties.get("persist.baikal.fps.default","6"));
                if( value != mDefaultFps ) { 
                    mDefaultFps = value;
                    if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"mDefaultFps changed=" + mDefaultFps);
                    setfps = mDefaultFps-1;
                }
            } catch( Exception e ) {
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"mDefaultFps:", e);
                mDefaultFps = 6;
                setfps = 5;
            }
        }
        //if( mActiveFrameRate != fps || setfps != fps ) {
            if( setHwFrameRateLocked(setfps, false) ) {
                mActiveFrameRate = fps;
            }
        //}
    }

    protected void setDeviceIdleModeLocked(boolean mode) {
        if( mDeviceIdleMode != mode ) {
            mDeviceIdleMode = mode;
            Runtime.setIdleMode(mode);
            BaikalSettings.setIdleMode(mode);
            //if( !mScreenMode ) {
            //    setIdlePerformanceMode(mode);
            //} 
            restoreProfileForCurrentMode();
        }
    }


    protected void onCallStateChangedLocked(int state, String incomingNumber) {
    }

    protected void onPreciseCallStateChangedLocked(PreciseCallState callState) {

        boolean state =  callState.getRingingCallState() > 0 ||
                         callState.getForegroundCallState() > 0 ||
                         callState.getBackgroundCallState() > 0;

        if( mPhoneCall != state ) {
            mPhoneCall = state;
    
            if( mPhoneCall ) {
                BaikalUtils.boost();
                setActivePerfProfileLocked("default");
                setActiveThermProfileLocked("incall");
            } 
        }
    }


    protected void setScreenModeLocked(boolean mode) {
        if( mScreenMode != mode ) {
            mScreenMode = mode;
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"Restore profile after screen mode changed mode=" + mScreenMode);
            getHandler().postDelayed( new Runnable() {
                @Override
                public void run() { 
                    restoreProfileForCurrentMode();
                }
            },500);
        }
    }

    protected void setProfileExternalLocked(String profile) {
        if( profile == null || profile.equals("") ) {
            restoreProfileForCurrentMode();
        } else {
            setActivePerfProfileLocked(profile);
        }   
    }

    protected void restoreProfileForCurrentMode() {
        if( mScreenMode ) {
            //setActiveFrameRateLocked(-1);
            if( getIdlePerformanceMode() ) {
                setIdlePerformanceMode(false);
            }
            //setHwPerfProfileLocked(mActivePerfProfile, true);
            //setHwThermProfileLocked(mActiveThermProfile, true);
            activateCurrentProfileLocked();
        } else {
            setActiveFrameRateLocked(0);
            if( Runtime.isIdleMode() /*&& BaikalSettings.getExtremeIdleEnabled() */) {
                setIdlePerformanceMode(true);
            } else {
                setHwPerfProfileLocked(mScreenOffPerfProfile, true);
                setHwThermProfileLocked(mScreenOffThermProfile, true);
            }
        }
    }

    protected void activateCurrentProfileLocked() {

            if( !mScreenMode ) {
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"Can't activate profile screen mode is " + mScreenMode);
                return;
            }


            AppProfile profile = null;
            synchronized(mCurrentProfileSync) {
                profile = mCurrentProfile;
            }

            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"Activate current profile profile=" + profile);

            if( profile == null /*|| uid < Process.FIRST_APPLICATION_UID */) {
                setReaderModeLocked(false);
                setActivePerfProfileLocked("default");
                setActiveThermProfileLocked("default");
                setActiveFrameRateLocked(-1);
                Actions.sendBrightnessOverrideChanged(setBrightnessOverrideLocked(0));
                setRotation(-1);
            } else {
                setActivePerfProfileLocked(profile.mPerfProfile);
                setActiveThermProfileLocked(profile.mThermalProfile);
                setActiveFrameRateLocked(profile.mFrameRate-1);
                if( mReaderModeAvailable ) setReaderModeLocked(profile.mReader);
                else setReaderModeLocked(false);
                Actions.sendBrightnessOverrideChanged(setBrightnessOverrideLocked(profile.mBrightness));
                setRotation(profile.mRotation-1);
            }
    }

    protected void setTopAppLocked(int uid, String packageName) {

        if( packageName != null )  packageName = packageName.split(":")[0];

        if( uid != mTopUid || packageName != mTopPackageName ) {
            mTopUid = uid;
            mTopPackageName = packageName;

            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"topAppChanged uid=" + uid + ", packageName=" + packageName);

            mReaderModeAvailable  = SystemProperties.get("sys.baikal.reader", "1").equals("1");
            BaikalSettings.setTopApp(mTopUid, mTopPackageName);

            AppProfile profile = mAppSettings.getProfile(/*uid,*/packageName);
            synchronized(mCurrentProfileSync) {
                mCurrentProfile = profile;
            }

            activateCurrentProfileLocked();
        }
    }

    protected void setChargerModeLocked(boolean mode) {
        if( mOnCharger != mode ) {
            mOnCharger = mode;
        }
    }

    protected void setReaderModeLocked(boolean mode) {
        if( mReaderModeAvailable ) {
            if( mReaderMode != mode ) {
                mReaderMode = mode;
                Actions.sendReaderModeChanged(mode);
            }
        } else {
            if( mode ) {
                Slog.w(TAG,"setReaderModeLocked. Reader Mode not available!");
            }
        }
    }
   
    private final BroadcastReceiver mTopAppReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (AppProfileManager.this) {
                String action = intent.getAction();
                String packageName = (String)intent.getExtra(Actions.EXTRA_PACKAGENAME);
                int uid = (int)intent.getExtra(Actions.EXTRA_UID);
                setTopAppLocked(uid,packageName);
            }
        }
    };

    private final BroadcastReceiver mIdleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (AppProfileManager.this) {
                String action = intent.getAction();
                boolean mode = (boolean)intent.getExtra(Actions.EXTRA_BOOL_MODE);
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"idleChanged mode=" + mode);
                setDeviceIdleModeLocked(mode);
            }
        }
    };

    private final BroadcastReceiver mChargerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (AppProfileManager.this) {
                String action = intent.getAction();
                boolean mode = (boolean)intent.getExtra(Actions.EXTRA_BOOL_MODE);
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"chargerChanged mode=" + mode);
                setChargerModeLocked(mode);
            }
        }
    };


    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (AppProfileManager.this) {
                String action = intent.getAction();
                String profile = (String)intent.getExtra("profile");
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"setProfile profile=" + profile);
                setProfileExternalLocked(profile);
            }
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (AppProfileManager.this) {
                String action = intent.getAction();
                boolean mode = (boolean)intent.getExtra(Actions.EXTRA_BOOL_MODE);
                if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"screenChanged mode=" + mode);
                setScreenModeLocked(mode);
            }
        }
    };

    private void setHwPerfProfileLocked(String profile, boolean override) {
        if( (!mScreenMode || mIdleProfileActive) && !override ) return;
        mActivePerfProfile = profile;
        SystemPropertiesSet("baikal.perf.profile", profile);
    }

    private void setHwThermProfileLocked(String profile, boolean override) {
        if( (!mScreenMode || mIdleProfileActive) && !override ) return;
        mActiveThermProfile = profile;
        SystemPropertiesSet("baikal.therm.profile", profile);
    }

    private boolean setHwFrameRateLocked(int fps, boolean override) {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"setHwFrameRateLocked fps=" + fps);
        if( !mVariableFps ) return true;
        if( mIdleProfileActive && !override ) return false;
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        if( fps == -1 || fps == -2 ) { 
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"setHwFrameRateLocked default=" + mDefaultFps);
            fps = mDefaultFps-1;
        }
        if( fps < 0 || fps > 5  ) fps = 3;
        if( fps == 5 ) fps = -1;
        data.writeInt(fps);
        try {
            ServiceManager.getService("SurfaceFlinger").transact(1035, data, (Parcel) null, 0);
        } catch (RemoteException e) {
            data.recycle();
            return false;
        }
        data.recycle();
        SystemPropertiesSet("baikal.fps_override", Integer.toString(fps));
        return true;
    }

    private void setIdlePerformanceMode(boolean idle) {
        if( mIdleProfileActive == idle ) return;
        mIdleProfileActive = idle;
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"baikal.idle mode=" + idle);
        SystemPropertiesSet("baikal.idle", idle ? "1" : "0");
        if( !idle ) {
            if( mScreenMode ) {
                //setHwPerfProfileLocked(mActivePerfProfile,true);
                //setHwThermProfileLocked(mActiveThermProfile,true);
            } else {
                setHwPerfProfileLocked(mScreenOffPerfProfile,true);
                setHwThermProfileLocked(mScreenOffThermProfile,true);
            }
        } else {
            setHwPerfProfileLocked(mIdlePerfProfile,true);
            setHwThermProfileLocked(mIdleThermProfile,true);
        }
    }

    private boolean getIdlePerformanceMode() {
        return mIdleProfileActive;//SystemProperties.get("baikal.idle","0").equals("1");
    }

    private void SystemPropertiesSet(String key, String value) {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) {
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.d(TAG, "SystemProperties.set("+key+","+value+")");
        }
        try {
            SystemProperties.set(key,value);
        }
        catch( Exception e ) {
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.e(TAG, "SystemPropertiesSet: unable to set property "+key+" to "+value);
        }
    }

    private int setBrightnessOverrideLocked(int brightness) {
        int mBrightnessOverride = -1;
        switch( brightness ) {
            case 0:
                mBrightnessOverride = -1;
                break;
            case 10:
                mBrightnessOverride = -2;
                break;
            case 12:
                mBrightnessOverride = -3;
                break;

            case 13:
                mBrightnessOverride = -4;
                break;
            case 14:
                mBrightnessOverride = -5;
                break;

            case 11:
                mBrightnessOverride = PowerManager.BRIGHTNESS_ON;
                break;
            case 1:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 2)/100; // 3
                break;
            case 2:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 3)/100; // 4
                break;
            case 3:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 4)/100; // 6
                break;
            case 4:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 6)/100; // 8
                break;
            case 5:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 8)/100; // 10
                break;
            case 6:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 15)/100; // 20
                break;
            case 7:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 30)/100; // 35
                break;
            case 8:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 60)/100; // 60
                break;
            case 9:
                mBrightnessOverride = (PowerManager.BRIGHTNESS_ON * 80)/100; // 100
                break;
            default:
                mBrightnessOverride = -1;
        }
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"mBrightnessOverride=" + mBrightnessOverride);
        return mBrightnessOverride;
    }


    private void setRotation(int rotation) {
        setRotationLock(rotation);
    }

    private void setRotationLock(final int rotation) {
        Settings.Global.putInt(getContext().getContentResolver(),
                        Settings.Global.BAIKALOS_DEFAULT_ROTATION,rotation);
    }


    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        /**
         * Callback invoked when device call state changes.
         * @param state call state
         * @param incomingNumber incoming call phone number. If application does not have
         * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} permission, an empty
         * string will be passed as an argument.
         *
         * @see TelephonyManager#CALL_STATE_IDLE
         * @see TelephonyManager#CALL_STATE_RINGING
         * @see TelephonyManager#CALL_STATE_OFFHOOK
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"PhoneStateListener: onCallStateChanged(" + state + "," + incomingNumber + ")");

            synchronized (AppProfileManager.this) {
                onCallStateChangedLocked(state,incomingNumber);
            }

        // default implementation empty
        }

        /**
         * Callback invoked when precise device call state changes.
         *
         * @hide
         */
        @Override
        public void onPreciseCallStateChanged(PreciseCallState callState) {
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"PhoneStateListener: onPreciseCallStateChanged(" + callState + ")");
            synchronized (AppProfileManager.this) {
                onPreciseCallStateChangedLocked(callState);
            }
        }

    };
}

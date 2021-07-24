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
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Process;
import android.os.SystemClock;

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

    private static final int MESSAGE_APP_PROFILE_UPDATE = BaikalConstants.MESSAGE_APP_PROFILE + 100;

    private static Object mLock = new Object();

    private AppProfileSettings mAppSettings;
    private IPowerManager mPowerManager;

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

    private boolean mPerfAvailable = false;
    private boolean mThermAvailable = false;

    private boolean mPerformanceBoostLocked = false;
    private boolean mGpuBoostLocked = false;
    private boolean mSustainedPerformanceLocked = false;
    private boolean mLaunchBoostLocked = false;
    private boolean mCameraBoostLocked = false;
    private boolean mAudioBoostLocked = false;

    private boolean mBaikalPowerHal = true;

    private static IPowerHalWrapper mPowerWrapper = null;

    TelephonyManager mTelephonyManager;

    static AppProfileManager mInstance;

    public static AppProfile getCurrentProfile() {
        synchronized(mCurrentProfileSync) {
            return mCurrentProfile;
        }
    }

    static AppProfileManager Instance() {
        return mInstance;
    }

    @Override
    protected void initialize() {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"initialize()");                
        synchronized(mLock) {

            mInstance = this;
            mAppSettings = AppProfileSettings.getInstance(getHandler(), getContext(), getContext().getContentResolver(), null, true);

//            mPowerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            mPowerManager = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));

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

            mPerfAvailable = SystemProperties.get("baikal.eng.perf", "0").equals("1");
            mThermAvailable = SystemProperties.get("baikal.eng.therm", "0").equals("1");

            
    
        }
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
                /*mGpuBoostLocked = true;
                mActivePerfProfile = "temporary";
                SystemPropertiesSet("baikal.perf.incall", "1"); 
                //scheduleBoostCancel(4000);

                //BaikalUtils.boost();
                //setActivePerfProfileLocked("default");
                setActiveThermProfileLocked("incall");*/
                setPowerHint(2,4000);
            } else {
                //SystemPropertiesSet("baikal.perf.incall", "0"); 
                setPowerHint(2,2000);
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
                BaikalSettings.setKeepOn(false);
            } else {
                setActivePerfProfileLocked(profile.mPerfProfile);
                setActiveThermProfileLocked(profile.mThermalProfile);
                setActiveFrameRateLocked(profile.mFrameRate-1);
                if( mReaderModeAvailable ) setReaderModeLocked(profile.mReader);
                else setReaderModeLocked(false);
                Actions.sendBrightnessOverrideChanged(setBrightnessOverrideLocked(profile.mBrightness));
                setRotation(profile.mRotation-1);
                BaikalSettings.setKeepOn(profile.mKeepOn);
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
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG,"setHwPerfProfileLocked profile=" + profile + ", ovr=" + override);
        if( (!mScreenMode || mIdleProfileActive) && !override ) return;
        if( !mPerfAvailable ) return;

        if( !isBoostLocked() ) {
            mActivePerfProfile = profile;
            if( !mBaikalPowerHal )
                SystemPropertiesSet("baikal.perf.profile", profile);
            else 
                wrapperSendPowerHint(10000, AppProfileSettings.perfProfileIdFromName(profile));
        } 
        return;
    }

    private void setHwThermProfileLocked(String profile, boolean override) {
        if( (!mScreenMode || mIdleProfileActive) && !override ) return;
        mActiveThermProfile = profile;
        if( mThermAvailable )
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


    public static void setPowerWrapper(IPowerHalWrapper wrapper) {
        mPowerWrapper = wrapper;
    }


    private void wrapperSendPowerHint(int hintId, int data) {
        if( mPowerWrapper != null ) {
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "wrapperSendPowerHint " + hintId + " data=" + data);
            mPowerWrapper.wrapperSetPowerBoost(hintId,data);
        } else {
            if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "wrapperSendPowerHint wrapper = null");
        }
    }

    public boolean boost(int boost, int durationMs) {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) Slog.i(TAG, "Boost " + boost + " dur=" + durationMs);
        if( !mPerfAvailable ) return false;

        if( !mBaikalPowerHal ) return false;

        //SystemPropertiesSet("baikal.perf.boost", durationMs <= 0 ? "0" : "1" ); 

        if( durationMs < 0 ) {
            //mPerformanceBoostLocked = false;
            //if( !isBoostLocked() ) {    
            //    getHandler().removeMessages(MESSAGE_APP_PROFILE_UPDATE);
            //    restoreProfileForCurrentMode();
            //}                                            
            wrapperSendPowerHint(12000, 0);
        } else if( durationMs > 0 ) {
            //mActivePerfProfile = "temporary";
            //if( durationMs > 0 ) scheduleBoostCancel(durationMs);
            wrapperSendPowerHint(12000, 1);
            scheduleBoostCancel(durationMs);
        }

        return true;

    }

    String [] mPowerModes = {
    "MODE_DOUBLE_TAP_TO_WAKE",
    "MODE_LOW_POWER",
    "MODE_SUSTAINED_PERFORMANCE",
    "MODE_FIXED_PERFORMANCE ",
    "MODE_VR",
    "MODE_LAUNCH",
    "MODE_EXPENSIVE_RENDERING",
    "MODE_INTERACTIVE",
    "MODE_DEVICE_IDLE",
    "MODE_DISPLAY_INACTIVE",
    "MODE_AUDIO_STREAMING_LOW_LATENCY",
    "MODE_CAMERA_STREAMING_SECURE",
    "MODE_CAMERA_STREAMING_LOW",
    "MODE_CAMERA_STREAMING_MID",
    "MODE_CAMERA_STREAMING_HIGH"
     };


    public boolean setPowerMode(int mode, boolean enabled) {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) { 
            if( mode >=0 && mode <=14 ) Slog.i(TAG, "setPowerMode " + mPowerModes[mode] + " en=" + enabled);
            else Slog.i(TAG, "setPowerMode " + mode + " en=" + enabled);
        }
        if( mode == 0 ) return false;
        if( !mPerfAvailable ) return false;

        if( !mBaikalPowerHal ) return false;


        return true;
    }


    String [] mPowerHints = {
        "POWER_HINT_NONE",
        "POWER_HINT_VSYNC",
        "POWER_HINT_INTERACTION",
        "POWER_HINT_VIDEO_ENCODE",
        "POWER_HINT_VIDEO_DECODE",
        "POWER_HINT_LOW_POWER",
        "POWER_HINT_SUSTAINED_PERFORMANCE",
        "POWER_HINT_VR_MODE",
        "POWER_HINT_LAUNCH",
        "POWER_HINT_AUDIO_STREAMING",
        "POWER_HINT_AUDIO_LOW_LATENCY",
        "POWER_HINT_CAMERA_LAUNCH",
        "POWER_HINT_CAMERA_STREAMING",
        "POWER_HINT_CAMERA_SHOT",
        "POWER_HINT_EXPENSIVE_RENDERING",
    };



    public boolean setPowerHint(int hintId, int data) {
        if( BaikalConstants.BAIKAL_DEBUG_APP_PROFILE ) {
            if( hintId >= 0 && hintId <= 14 ) Slog.i(TAG, "setPowerHint " + mPowerHints[hintId] + " data=" + data);
            else Slog.i(TAG, "setPowerHint " + hintId + " data=" + data);
        }
        if( !mPerfAvailable ) return false;
        if( !mBaikalPowerHal ) return false;

        switch(hintId) {
            case 14: /// POWER_HINT_EXPENSIVE_RENDERING
                /*mActivePerfProfile = "temporary";
                SystemPropertiesSet("baikal.perf.hint_exp_rend", "" + data); 
                if( data != 0 ) {
                    mGpuBoostLocked = true;
                } else {
                    if( mGpuBoostLocked ) {
                        mGpuBoostLocked = false;
                        if( !isBoostLocked() ) {    
                            getHandler().removeMessages(MESSAGE_APP_PROFILE_UPDATE);
                            restoreProfileForCurrentMode();
                        }
                    }
                }*/
                wrapperSendPowerHint(12002, data); // Expensive Rendering
                break;

            case 2:
                
                if( data > 0 )  {   
                    //mGpuBoostLocked = true;
                    //mActivePerfProfile = "temporary";
                    //SystemPropertiesSet("baikal.perf.hint_gpu", "1"); 
                    wrapperSendPowerHint(12000, 1); // Interaction
                    scheduleBoostCancel(data);
                } else if( data < 0 ) {
                    wrapperSendPowerHint(12000, 0); // Interaction
                }
                
                break;

            case 11:
            case 12:
            case 13:
                /*
                mActivePerfProfile = "temporary";
                SystemPropertiesSet("baikal.perf.hint_camera", "" + data); 
                if( data != 0 ) {
                    mCameraBoostLocked = true;
                } else {
                    if( mAudioBoostLocked ) { 
                        mAudioBoostLocked = false;
                        if( !isBoostLocked() ) {
                            getHandler().removeMessages(MESSAGE_APP_PROFILE_UPDATE);
                            restoreProfileForCurrentMode();
                        }
                    }
                }*/
                wrapperSendPowerHint(12004, data); // Camera
                break;

            case 9:
            case 10:
                /*
                mActivePerfProfile = "temporary";
                SystemPropertiesSet("baikal.perf.hint_audio", "" + data); 
                if( data != 0 ) {
                    mAudioBoostLocked = true;
                } else {
                    if( mAudioBoostLocked ) { 
                        mAudioBoostLocked = false;
                        if( !isBoostLocked() ) {
                            getHandler().removeMessages(MESSAGE_APP_PROFILE_UPDATE);
                            restoreProfileForCurrentMode();
                        }
                    }
                }*/

                wrapperSendPowerHint(12003, data); // Audio
                break;

            case 8:
                /*
                mActivePerfProfile = "temporary";
                SystemPropertiesSet("baikal.perf.hint_launch", "" + data); 
                if( data != 0 ) {
                    mLaunchBoostLocked = true;
                    //scheduleBoostCancel(5000);
                } else {
                    if( mLaunchBoostLocked ) { 
                        mLaunchBoostLocked = false;
                        if( !isBoostLocked() ) { 
                            getHandler().removeMessages(MESSAGE_APP_PROFILE_UPDATE);
                            restoreProfileForCurrentMode();
                        }
                    }
                }*/
                wrapperSendPowerHint(12001, data); // LUNCH
                break;
        }

        return true;
    }

    private boolean isBoostLocked() {
        return mPerformanceBoostLocked || 
            mGpuBoostLocked || 
            mSustainedPerformanceLocked || 
            mLaunchBoostLocked ||
            mCameraBoostLocked ||
            mAudioBoostLocked;
    }


    private long nextTime = 0;
    public void scheduleBoostCancel(long timeout) {
        final long now = SystemClock.elapsedRealtime();
        
        if( (now + timeout) > nextTime ) { 
            Slog.i(TAG, "MESSAGE_APP_PROFILE_UPDATE in " + timeout + " msec");
            nextTime = now + timeout;
            getHandler().removeMessages(MESSAGE_APP_PROFILE_UPDATE);

        	Message msg = getHandler().obtainMessage(MESSAGE_APP_PROFILE_UPDATE);
            getHandler().sendMessageDelayed(msg, timeout);
        }
    }


    @Override
    public boolean onMessage(Message msg) {
    	switch(msg.what) {
    	    case MESSAGE_APP_PROFILE_UPDATE:
                Slog.i(TAG, "MESSAGE_APP_PROFILE_UPDATE cancel all boost requests");
                /*mLaunchBoostLocked = false;
                mGpuBoostLocked = false;
                mSustainedPerformanceLocked = false;
                mPerformanceBoostLocked = false;
                mCameraBoostLocked = false;
                mAudioBoostLocked = false;
                SystemPropertiesSet("baikal.perf.boost", "0" ); 
                SystemPropertiesSet("baikal.perf.hint_gpu", "0" ); 
                SystemPropertiesSet("baikal.perf.hint_exp_rend", "0" ); 
                SystemPropertiesSet("baikal.perf.hint_audio", "0" ); 
                SystemPropertiesSet("baikal.perf.hint_camera", "0" ); 
                restoreProfileForCurrentMode();*/
                wrapperSendPowerHint(12000, 0);
    		return true;
    	}
    	return false;
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
            //synchronized (AppProfileManager.this) {
                onCallStateChangedLocked(state,incomingNumber);
            //}

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
            //synchronized (AppProfileManager.this) {
                onPreciseCallStateChangedLocked(callState);
            //}
        }

    };
}

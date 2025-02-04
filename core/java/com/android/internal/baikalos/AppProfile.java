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
import android.util.KeyValueListParser;

import android.os.UserHandle;
import android.os.Handler;
import android.os.Message;

import android.content.Context;
import android.content.ContentResolver;

import android.net.Uri;

import android.database.ContentObserver;

import android.provider.Settings;

import java.util.HashMap;

public class AppProfile {

    private static final String TAG = "Baikal.AppProfile";

    public String mPackageName;
    public int mBrightness;
    public String mPerfProfile;
    public String mThermalProfile;
    public int mFrameRate;
    public boolean mReader;
    public boolean mPinned;
    public int mBackground;
    public boolean mStamina;
    public boolean mRequireGms;
    public boolean mBootDisabled;
    public boolean mIgnoreAudioFocus;
    public int mRotation;
    public int mAudioMode;
    public int mSpoofDevice;
    public boolean mKeepOn;
    public boolean mPreventHwKeyAttestation;

    public AppProfile() {
        mPerfProfile = "default";
        mThermalProfile = "default";
        mPackageName = "";
        mFrameRate = 0;
        mRotation = 0;
        mAudioMode = 0;
        mSpoofDevice = 0;
    }

    public boolean isDefault() {
        if( mBrightness == 0 &&
            !mReader &&
            !mPinned &&
            !mStamina &&
            !mRequireGms &&
            !mBootDisabled &&
            mFrameRate == 0 &&
            mBackground == 0 &&
            !mIgnoreAudioFocus &&
            mRotation == 0 &&
            mAudioMode == 0 &&
            mSpoofDevice == 0 &&
            !mKeepOn &&
            !mPreventHwKeyAttestation &&
            mPerfProfile.equals("default") &&
            mThermalProfile.equals("default") ) return true;
        return false;
    }

    public String Serialize() {
        if( mPackageName == null || mPackageName.equals("") ) return null;
        String result =  "pn=" + mPackageName;
        if( mBrightness != 0 ) result += "," + "br=" + mBrightness;
        if( ! mPerfProfile.equals("default") ) result += "," + "pp=" + (mPerfProfile != null ? mPerfProfile : "");
        if( ! mThermalProfile.equals("default") ) result += "," + "tp=" + (mThermalProfile != null ? mThermalProfile : "");
        if( mReader ) result +=  "," + "rm=" + mReader;
        if( mPinned ) result +=  "," + "pd=" + mPinned;
        if( mFrameRate != 0 ) result +=  "," + "fr=" + mFrameRate;
        if( mStamina ) result +=  "," + "as=" + mStamina;
        if( mBackground != 0 ) result +=  "," + "bk=" + mBackground;
        if( mRequireGms ) result +=  "," + "gms=" + mRequireGms;
        if( mBootDisabled ) result +=  "," + "bt=" + mBootDisabled;
        if( mIgnoreAudioFocus ) result +=  "," + "af=" + mIgnoreAudioFocus;
        if( mRotation != 0 ) result +=  "," + "ro=" + mRotation;
        if( mAudioMode != 0 ) result +=  "," + "am=" + mAudioMode;
        if( mSpoofDevice != 0 ) result +=  "," + "sd=" + mSpoofDevice;
        if( mKeepOn ) result +=  "," + "ko=" + mKeepOn;
        if( mPreventHwKeyAttestation ) result +=  "," + "pka=" + mPreventHwKeyAttestation;
        return result;
    }

    public static AppProfile Deserialize(String profileString) {

        KeyValueListParser parser = new KeyValueListParser(',');

        AppProfile profile = new AppProfile();
        try {
            parser.setString(profileString);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Bad profile settings", e);
            return null;
        }
        profile.mPackageName = parser.getString("pn",null);
        if( profile.mPackageName == null || profile.mPackageName.equals("") ) return null;
        try {
            profile.mBrightness = parser.getInt("br",0);
            profile.mPerfProfile = parser.getString("pp","default");
            profile.mThermalProfile = parser.getString("tp","default");
            profile.mReader = parser.getBoolean("rm",false);
            profile.mPinned = parser.getBoolean("pd",false);
            profile.mStamina = parser.getBoolean("as",false);
            profile.mFrameRate = parser.getInt("fr",0);
            profile.mBackground = parser.getInt("bk",0);
            profile.mRequireGms = parser.getBoolean("gms",false);
            profile.mBootDisabled = parser.getBoolean("bt",false);
            profile.mIgnoreAudioFocus = parser.getBoolean("af",false);
            profile.mRotation = parser.getInt("ro",0);
            profile.mAudioMode = parser.getInt("am",0);
            profile.mSpoofDevice = parser.getInt("sd",0);
            profile.mKeepOn = parser.getBoolean("ko",false);
            profile.mPreventHwKeyAttestation = parser.getBoolean("pka",false);
        } catch( Exception e ) {

        }
        return profile;
    }
}

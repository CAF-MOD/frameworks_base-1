/*
 * Copyright (C) 2019 The OmniROM Project
 * Copyright (C) 2020 crDroid Android Project
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.text.TextUtils;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import javax.inject.Inject;

public class RRInfoTile extends QSTileImpl<BooleanState> {
    private boolean mListening;

    private static final int SETTING_VALUE_QUERY = 2;
    private static final int SETTING_VALUE_ON = 1;
    private static final int SETTING_VALUE_OFF = 0;

    @VisibleForTesting
    static final String SURFACE_FLINGER_SERVICE_KEY = "SurfaceFlinger";
    @VisibleForTesting
    static final int SURFACE_FLINGER_CODE = 1034;

    private static final String SURFACE_COMPOSER_INTERFACE_KEY = "android.ui.ISurfaceComposer";

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_rr_info);

    private final IBinder mSurfaceFlinger;

    @Inject
    public RRInfoTile(QSHost host) {
        super(host);
        mSurfaceFlinger = ServiceManager.getService(SURFACE_FLINGER_SERVICE_KEY);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        toggleState();
        refreshState();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }

    @Override
    public void handleLongClick() {
    }

    protected void toggleState() {
        setRefreshRateOverlay(!getRefreshRateOverlay());
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_rrinfo_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_rrinfo_label);
        state.icon = mIcon;
	    if (getRefreshRateOverlay()) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_rrinfo_on);
            state.state = Tile.STATE_ACTIVE;
	    } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_rrinfo_off);
            state.state = Tile.STATE_INACTIVE;
	    }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
    }


    @VisibleForTesting
    boolean getRefreshRateOverlay() {
        // magic communication with surface flinger.
        try {
            if (mSurfaceFlinger != null) {
                final Parcel data = Parcel.obtain();
                final Parcel reply = Parcel.obtain();
                data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE_KEY);
                data.writeInt(SETTING_VALUE_QUERY);
                mSurfaceFlinger.transact(SURFACE_FLINGER_CODE, data, reply, 0 /* flags */);
                final boolean enabled = reply.readBoolean();
                reply.recycle();
                data.recycle();
                return enabled;
            }
        } catch (RemoteException ex) {
            // intentional no-op
        }
        return false;
    }

    @VisibleForTesting
    void setRefreshRateOverlay(boolean isEnabled) {
        try {
            if (mSurfaceFlinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE_KEY);
                final int showRefreshRate = isEnabled ? SETTING_VALUE_ON : SETTING_VALUE_OFF;
                data.writeInt(showRefreshRate);
                mSurfaceFlinger.transact(SURFACE_FLINGER_CODE, data,
                        null /* reply */, 0 /* flags */);
                data.recycle();
            }
        } catch (RemoteException ex) {
            // intentional no-op
        }
    }

}

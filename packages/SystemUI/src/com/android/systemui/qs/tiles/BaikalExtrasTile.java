/*
 * Copyright (C) 2015 The Dirty Unicorns Project
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
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.aicp.PackageUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysUIToast;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

public class BaikalExtrasTile extends QSTileImpl<BooleanState> {
    private boolean mListening;
    private final ActivityStarter mActivityStarter;

    private static final String TAG = "BaikalExtrasTile";

    private static final String BE_PKG_NAME = "ru.baikalos.extras";

    private static final Intent BAIKAL_EXTRAS = new Intent()
        .setComponent(new ComponentName(BE_PKG_NAME,
        "ru.baikalos.extras.SettingsActivity"));

    @Inject
    public BaikalExtrasTile(QSHost host) {
        super(host);
        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        mHost.collapsePanels();
        startBaikalExtras();
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_baikal_extras_label);
    }

    protected void startBaikalExtras() {
        mActivityStarter.postStartActivityDismissingKeyguard(BAIKAL_EXTRAS, 0);
    }

    private boolean isBEAvailable(){
        boolean isInstalled = false;
        boolean isNotHidden = false;
        isInstalled = PackageUtils.isPackageInstalled(mContext, BE_PKG_NAME);
        isNotHidden = PackageUtils.isPackageAvailable(mContext, BE_PKG_NAME);
        return isInstalled || isNotHidden;
    }

    @Override
    public boolean isAvailable(){
      return isBEAvailable();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.icon = ResourceIcon.get(R.drawable.ic_qs_baikal_extras);
        state.label = mContext.getString(R.string.quick_baikal_extras_label);
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }
}

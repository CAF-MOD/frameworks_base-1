/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.systemui;

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_NONE;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;
import static android.provider.Settings.System.AICP_SHOW_BATTERY_IMAGE;
import static android.provider.Settings.Secure.STATUS_BAR_BATTERY_STYLE;

import static com.android.systemui.DejankUtils.whitelistIpcs;
import static com.android.systemui.util.SysuiLifecycle.viewAttachLifecycle;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StyleRes;

import com.android.settingslib.Utils;
import com.android.settingslib.graph.BatteryMeterDrawableBase;
import com.android.settingslib.graph.ThemedBatteryDrawable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.Utils.DisableStateTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.text.NumberFormat;

public class BatteryMeterView extends LinearLayout implements
        BatteryStateChangeCallback, Tunable, DarkReceiver, ConfigurationListener {


    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ON, MODE_OFF, MODE_ESTIMATE})
    public @interface BatteryPercentMode {}
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_ESTIMATE = 3;

    private final String mSlotBattery;
    private ThemedBatteryDrawable mDrawable;
    private ImageView mBatteryIconView;
    private final CurrentUserTracker mUserTracker;
    private TextView mBatteryPercentView;

    private BatteryController mBatteryController;
    private SettingObserver mSettingObserver;
    private final @StyleRes int mPercentageStyleId;
    private int mTextColor;
    private int mLevel;
    private int mShowPercentMode = MODE_DEFAULT;
    private boolean mForceShowPercent = false;
    private boolean mShowPercentAvailable;
    private boolean mShowBatteryImage;
    // Some places may need to show the battery conditionally, and not obey the tuner
    private boolean mIgnoreTunerUpdates;
    private boolean mIsSubscribedForTunerUpdates;
    private boolean mCharging;
    private boolean mPowerSave;

    private DualToneHandler mDualToneHandler;
    private int mUser;

    private final Context mContext;
    private final int mFrameColor;

    /**
     * Whether we should use colors that adapt based on wallpaper/the scrim behind quick settings.
     */
    private boolean mUseWallpaperTextColors;

    private int mNonAdaptedSingleToneColor;
    private int mNonAdaptedForegroundColor;
    private int mNonAdaptedBackgroundColor;
    private boolean mPowerSaveEnabled;
    private boolean mStaminaEnabled;

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        BroadcastDispatcher broadcastDispatcher = Dependency.get(BroadcastDispatcher.class);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.meter_background_color));
        mPercentageStyleId = atts.getResourceId(R.styleable.BatteryMeterView_textAppearance, 0);
        mFrameColor = frameColor;
        mDrawable = new ThemedBatteryDrawable(context, frameColor);
        atts.recycle();

        mSettingObserver = new SettingObserver(new Handler(context.getMainLooper()));
        mShowPercentAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_battery_percentage_setting_available);


        addOnAttachStateChangeListener(
                new DisableStateTracker(DISABLE_NONE, DISABLE2_SYSTEM_ICONS,
                        Dependency.get(CommandQueue.class)));

        setupLayoutTransition();

        mSlotBattery = context.getString(
                com.android.internal.R.string.status_bar_battery);
        mBatteryIconView = new ImageView(context);
        mBatteryIconView.setImageDrawable(mDrawable);
        final MarginLayoutParams mlp = new MarginLayoutParams(
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
        mlp.setMargins(0, 0, 0,
                getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
        addView(mBatteryIconView, mlp);

        mBatteryPercentView = loadPercentView();
        addView(mBatteryPercentView, new ViewGroup.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT));

        updateShowPercent(false);
        mDualToneHandler = new DualToneHandler(context);
        // Init to not dark at all.
        onDarkChanged(new Rect(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        mUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                mUser = newUserId;
                getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
                getContext().getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(SHOW_BATTERY_PERCENT), false, mSettingObserver,
                        newUserId);
                updateShowPercent(false);
            }
        };

        setClipChildren(false);
        setClipToPadding(false);
        Dependency.get(ConfigurationController.class).observe(viewAttachLifecycle(this), this);
    }

    private void setupLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

        setLayoutTransition(transition);
    }

    public void setForceShowPercent(boolean show) {
        setPercentShowMode(show ? MODE_ON : MODE_DEFAULT);
    }

    /**
     * Force a particular mode of showing percent
     *
     * 0 - No preference
     * 1 - Force on
     * 2 - Force off
     * @param mode desired mode (none, on, off)
     */
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        mShowPercentMode = mode;
        updateShowPercent(false);
    }

    public void setPercentShowMode(@BatteryPercentMode int mode, boolean forceShow) {
        mShowPercentMode = mode;
        updateShowPercent(forceShow);
    }

    /**
     * Set {@code true} to turn off BatteryMeterView's subscribing to the tuner for updates, and
     * thus avoid it controlling its own visibility
     *
     * @param ignore whether to ignore the tuner or not
     */
    public void setIgnoreTunerUpdates(boolean ignore) {
        mIgnoreTunerUpdates = ignore;
        updateTunerSubscription();
    }

    private void updateTunerSubscription() {
        if (mIgnoreTunerUpdates) {
            unsubscribeFromTunerUpdates();
        } else {
            subscribeForTunerUpdates();
        }
    }

    private void subscribeForTunerUpdates() {
        if (mIsSubscribedForTunerUpdates || mIgnoreTunerUpdates) {
            return;
        }

        Dependency.get(TunerService.class)
                .addTunable(this, StatusBarIconController.ICON_BLACKLIST,
                STATUS_BAR_BATTERY_STYLE);
        mIsSubscribedForTunerUpdates = true;
    }

    private void unsubscribeFromTunerUpdates() {
        if (!mIsSubscribedForTunerUpdates) {
            return;
        }

        Dependency.get(TunerService.class).removeTunable(this);
        mIsSubscribedForTunerUpdates = false;
    }

    /**
     * Sets whether the battery meter view uses the wallpaperTextColor. If we're not using it, we'll
     * revert back to dark-mode-based/tinted colors.
     *
     * @param shouldUseWallpaperTextColor whether we should use wallpaperTextColor for all
     *                                    components
     */
    public void useWallpaperTextColor(boolean shouldUseWallpaperTextColor) {
        if (shouldUseWallpaperTextColor == mUseWallpaperTextColors) {
            return;
        }

        mUseWallpaperTextColors = shouldUseWallpaperTextColor;

        if (mUseWallpaperTextColors) {
            updateColors(
                    Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor),
                    Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColorSecondary),
                    Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor));
        } else {
            updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor,
                    mNonAdaptedSingleToneColor);
        }
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        mDualToneHandler.setColorsFromContext(context);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            ArraySet<String> icons = StatusBarIconController.getIconBlacklist(
                    getContext(), newValue);
            setVisibility(icons.contains(mSlotBattery) ? View.GONE : View.VISIBLE);
        } else if (STATUS_BAR_BATTERY_STYLE.equals(key)) {
            updateBatteryStyle(newValue);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
        mUser = ActivityManager.getCurrentUser();
        subscribeForTunerUpdates();
        getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT), false, mSettingObserver, mUser);
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME),
                false, mSettingObserver);
        updateShowPercent(false);
        getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor(AICP_SHOW_BATTERY_IMAGE), false, mSettingObserver);
        updateShowImage();
        mUserTracker.startTracking();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mUserTracker.stopTracking();
        mBatteryController.removeCallback(this);
        getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
        unsubscribeFromTunerUpdates();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mCharging = charging;

        // text battery will always show percentage
        if (isCircleBattery()
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_PORTRAIT) {
            setForceShowPercent(pluggedIn);
        }
        // mDrawable.setCharging(pluggedIn) will invalidate the view
        mDrawable.setCharging(pluggedIn);
        mDrawable.setBatteryLevel(level);
        mCharging = pluggedIn;
        mLevel = level;
        if (mCharging != pluggedIn) {
            mCharging = pluggedIn;
            updateShowPercent(false);
        }
        updatePercentText();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSaveEnabled = isPowerSave;
        mDrawable.setPowerSaveEnabled(isPowerSave);
        if (mPowerSave != isPowerSave) {
            mPowerSave = isPowerSave;
            updateShowPercent(false);
            updatePercentText();
        }

    }

    public void onStaminaChanged(boolean isStamina) {
        //mDrawable.setPowerSaveEnabled(isPowerSave);
        if (mStaminaEnabled != isStamina) {
            mStaminaEnabled = isStamina;
            updateShowPercent(false);
            updatePercentText();
        }

    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    /**
     * Updates percent view by removing old one and reinflating if necessary
     */
    public void updatePercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
        mBatteryPercentView = loadPercentView();
        updateShowPercent(false);
    }

    private void updatePercentText() {
        if (mBatteryController == null) {
            return;
        }

        if (mBatteryPercentView != null) {
            if (mShowPercentMode == MODE_ESTIMATE && !mCharging) {
                mBatteryController.getEstimatedTimeRemainingString((String estimate) -> {
                    if (estimate != null) {
                        mBatteryPercentView.setText(estimate);
                        setContentDescription(getContext().getString(
                                R.string.accessibility_battery_level_with_estimate,
                                mLevel, estimate));
                    } else {
                        setPercentTextAtCurrentLevel();
                    }
                });
            } else {
                setPercentTextAtCurrentLevel();
            }
        } else {
            setContentDescription(
                    getContext().getString(mCharging ? R.string.accessibility_battery_level_charging
                            : R.string.accessibility_battery_level, mLevel));
        }
    }

    private void updateShowPercent(boolean forceShow) {
        final boolean showing = mBatteryPercentView != null;
        int style = whitelistIpcs(() -> Settings.System
                .getInt(getContext().getContentResolver(),
                SHOW_BATTERY_PERCENT, 0));
        if (mForceShowPercent || mPowerSave ) {
            style = 1; // Default view
        }

        if( mStaminaEnabled ) {
            style = 1;
        }
        switch (style) {
            case 1:
                if (!showing) {
                    mBatteryPercentView = loadPercentView();
                    if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
                    updatePercentText();
                    addView(mBatteryPercentView,
                            //0,
                            new ViewGroup.LayoutParams(
                                    LayoutParams.WRAP_CONTENT,
                                    LayoutParams.MATCH_PARENT));
                } else if (forceShow) {
                    updatePercentText();
                }
                mDrawable.setShowPercent(false);
                break;
            case 2:
                if (showing) {
                    removeView(mBatteryPercentView);
                    mBatteryPercentView = null;
                }
                mDrawable.setShowPercent(true);
                break;
            default:
                if (showing) {
                    removeView(mBatteryPercentView);
                    mBatteryPercentView = null;
                }
                mDrawable.setShowPercent(false);
                break;
        }
    }

    private void updateShowImage() {
        mShowBatteryImage = 1 == whitelistIpcs(() -> Settings.System
                .getInt(getContext().getContentResolver(),
                AICP_SHOW_BATTERY_IMAGE, 1));
        if (mBatteryIconView != null) {
            mBatteryIconView.setVisibility(mShowBatteryImage ? View.VISIBLE : View.GONE);
        }
    }

    private void setPercentTextAtCurrentLevel() {
        if (mBatteryPercentView != null) {
            // Use the high voltage symbol ⚡ (u26A1 unicode) but prevent the system
            // to load its emoji colored variant with the uFE0E flag
            String bolt = "\u26A1\uFE0E";
            CharSequence chargeIndicator = mCharging && !mShowBatteryImage
                    ? (bolt + " ") : "";
            try {
                mBatteryPercentView.setText(chargeIndicator +
                        NumberFormat.getPercentInstance().format(mLevel / 100f));
            } catch (RuntimeException e) {
                // don't crash if we dont have battery level yet.
            }
            if (mStaminaEnabled) {
                mBatteryPercentView.setTextColor(Color.rgb(0,200,0));
            } else if (mPowerSaveEnabled && !mCharging) {
                mBatteryPercentView.setTextColor(mDrawable.getPowersaveColor());
            } else if (mTextColor != 0) {
                mBatteryPercentView.setTextColor(mTextColor);
            }
        }
        setContentDescription(
                getContext().getString(mCharging ? R.string.accessibility_battery_level_charging
                        : R.string.accessibility_battery_level, mLevel));
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        scaleBatteryMeterViews();
    }

    @Override
    public void onOverlayChanged() {
        mShowPercentAvailable = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_battery_percentage_setting_available);
        updateShowPercent(false);
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    private void scaleBatteryMeterViews() {
        if (mBatteryPercentView != null) {
            FontSizeUtils.updateFontSize(mBatteryPercentView, R.dimen.qs_time_expanded_size);
        }

        if (mBatteryIconView == null) {
            return;
        }

        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(
                isBigCircleBattery() ? R.dimen.status_bar_battery_bigcircle_icon_height : R.dimen.status_bar_battery_icon_height);
        int batteryWidth = res.getDimensionPixelSize(
                isBigCircleBattery() ? R.dimen.status_bar_battery_bigcircle_icon_width : R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));
        scaledLayoutParams.setMargins(0, 0, 0, marginBottom);

        mBatteryIconView.setLayoutParams(scaledLayoutParams);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        float intensity = DarkIconDispatcher.isInArea(area, this) ? darkIntensity : 0;
        mNonAdaptedSingleToneColor = mDualToneHandler.getSingleColor(intensity);
        mNonAdaptedForegroundColor = mDualToneHandler.getFillColor(intensity);
        mNonAdaptedBackgroundColor = mDualToneHandler.getBackgroundColor(intensity);

        if (!mUseWallpaperTextColors) {
            updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor,
                    mNonAdaptedSingleToneColor);
        }
    }

    private void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mTextColor = singleToneColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(singleToneColor);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        String powerSave = mDrawable == null ? null : mDrawable.getPowerSaveEnabled() + "";
        CharSequence percent = mBatteryPercentView == null ? null : mBatteryPercentView.getText();
        pw.println("  BatteryMeterView:");
        pw.println("    mDrawable.getPowerSaveEnabled: " + powerSave);
        pw.println("    mBatteryPercentView.getText(): " + percent);
        pw.println("    mTextColor: #" + Integer.toHexString(mTextColor));
        pw.println("    mLevel: " + mLevel);
        pw.println("    mForceShowPercent: " + mForceShowPercent);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateShowPercent(false);
            updateShowImage();
            if (TextUtils.equals(uri.getLastPathSegment(),
                    Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME)) {
                // update the text for sure if the estimate in the cache was updated
                updatePercentText();
            }
        }
    }

    private void updateBatteryStyle(String styleStr) {
        final int style = styleStr == null ?
                BatteryMeterDrawableBase.BATTERY_STYLE_Q : Integer.parseInt(styleStr);
        mDrawable.setMeterStyle(style);

        switch (style) {
            case BatteryMeterDrawableBase.BATTERY_STYLE_TEXT:
                if (mBatteryIconView != null) {
                    removeView(mBatteryIconView);
                    mBatteryIconView = null;
                }
                break;
            default:
                if (mBatteryPercentView != null) {
                    removeView(mBatteryPercentView);
                    mBatteryPercentView = null;
                }
                if (mBatteryIconView == null) {
                    mBatteryIconView = new ImageView(mContext);
                    mBatteryIconView.setImageDrawable(mDrawable);
                    final MarginLayoutParams mlp = new MarginLayoutParams(
                            isBigCircleBattery()
                            ? getResources().getDimensionPixelSize(R.dimen.status_bar_battery_bigcircle_icon_width)
                            : getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                            isBigCircleBattery()
                            ? getResources().getDimensionPixelSize(R.dimen.status_bar_battery_bigcircle_icon_height)
                            : getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
                    mlp.setMargins(0, 0, 0,
                            getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
                    addView(mBatteryIconView, mlp);
                }
                break;
        }

        scaleBatteryMeterViews();
        updateShowPercent(false);
        updatePercentText();
    }

    private boolean isBigCircleBattery() {
        return getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_BIG_CIRCLE
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_BIG_DOTTED_CIRCLE;
    }

    private boolean isCircleBattery() {
        return isBigCircleBattery()
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_CIRCLE
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_DOTTED_CIRCLE
                || getMeterStyle() == BatteryMeterDrawableBase.BATTERY_STYLE_SOLID;
    }

    private int getMeterStyle() {
        return mDrawable.getMeterStyle();
    }

    public void setQsbHeader() {
        mForceShowPercent = true;
    }
}

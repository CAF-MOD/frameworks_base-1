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

public class BaikalConstants { 

    public static /*final*/ boolean BAIKAL_DEBUG = true;
    public static /*final*/ boolean BAIKAL_DEBUG_RAW = false;
    public static /*final*/ boolean BAIKAL_DEBUG_TEMPLATE = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_SENSORS = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_TORCH = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_TELEPHONY = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_TELEPHONY_RAW = BAIKAL_DEBUG_RAW | false;
    public static /*final*/ boolean BAIKAL_DEBUG_BLUETOOTH = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_ACTIONS = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_APP_PROFILE = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_DEV_PROFILE = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_SERVICES = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_ACTIVITY = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_ALARM = BAIKAL_DEBUG | false;
    public static /*final*/ boolean BAIKAL_DEBUG_BROADCAST = BAIKAL_DEBUG | false;

    public static final int MESSAGE_MIN = 10000;

    public static final int MESSAGE_SETTINGS = MESSAGE_MIN;
    public static final int MESSAGE_ACTIONS = MESSAGE_SETTINGS +1000;
    public static final int MESSAGE_SENSORS = MESSAGE_ACTIONS + 1000;
    public static final int MESSAGE_TELEPHONY = MESSAGE_SENSORS + 1000;
    public static final int MESSAGE_TORCH = MESSAGE_TELEPHONY + 1000;
    public static final int MESSAGE_ACTION = MESSAGE_TORCH + 1000;

    public static final int MESSAGE_APP_PROFILE = MESSAGE_ACTION + 1000;
    public static final int MESSAGE_DEV_PROFILE = MESSAGE_APP_PROFILE + 1000;

    public static final int MESSAGE_MAX = MESSAGE_DEV_PROFILE + 1000;


    public static final int DEBUG_MASK_ALL = 0x0001;
    public static final int DEBUG_MASK_TEMPLATE = 0x0002;
    public static final int DEBUG_MASK_SENSORS = 0x0004;
    public static final int DEBUG_MASK_TORCH = 0x0008;
    public static final int DEBUG_MASK_TELEPHONY = 0x0010;
    public static final int DEBUG_MASK_TELEPHONY_RAW = 0x0020;
    public static final int DEBUG_MASK_BLUETOOTH = 0x0040;
    public static final int DEBUG_MASK_ACTIONS = 0x0080;
    public static final int DEBUG_MASK_APP_PROFILE = 0x0100;
    public static final int DEBUG_MASK_DEV_PROFILE = 0x0200;
    public static final int DEBUG_MASK_SERVICES = 0x0400;
    public static final int DEBUG_MASK_ACTIVITY = 0x0800;
    public static final int DEBUG_MASK_ALARM = 0x1000;
    public static final int DEBUG_MASK_BROADCAST = 0x2000;

}

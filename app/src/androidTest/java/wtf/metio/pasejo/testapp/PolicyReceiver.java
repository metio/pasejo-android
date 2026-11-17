// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo.testapp;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Sets pasejo's managed configuration as the device owner, or gives up
 * device ownership. The instrumentation runs in pasejo's process, so it asks
 * this receiver, which runs as the test APK, through an ordered broadcast
 * and reads the outcome from the result code.
 */
public class PolicyReceiver extends BroadcastReceiver {
    public static final String SET = "wtf.metio.pasejo.testapp.SET_RESTRICTIONS";
    public static final String RELEASE = "wtf.metio.pasejo.testapp.RELEASE_DEVICE_OWNER";
    public static final String RESTRICTIONS = "restrictions";
    public static final String TARGET = "wtf.metio.pasejo";
    public static final int DONE = 1;
    public static final int FAILED = 2;

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        try {
            if (SET.equals(intent.getAction())) {
                Bundle restrictions = intent.getBundleExtra(RESTRICTIONS);
                policy.setApplicationRestrictions(
                        new ComponentName(context, TestAdmin.class),
                        TARGET,
                        restrictions == null ? new Bundle() : restrictions);
            } else if (RELEASE.equals(intent.getAction())) {
                policy.clearDeviceOwnerApp(context.getPackageName());
            } else {
                throw new IllegalArgumentException("unknown action " + intent.getAction());
            }
            setResultCode(DONE);
        } catch (RuntimeException error) {
            setResultData(error.toString());
            setResultCode(FAILED);
        }
    }
}

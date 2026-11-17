// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

/** A login form of another app: a username and a password field. */
public class LoginActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = Screens.column(this);
        column.addView(Screens.field(this, "Username", Screens.USERNAME, Screens.TEXT));
        column.addView(Screens.field(this, "Password", Screens.PASSWORD_HINT, Screens.PASSWORD));
        setContentView(column);
    }
}

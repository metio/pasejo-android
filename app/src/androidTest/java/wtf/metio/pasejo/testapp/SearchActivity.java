// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

/** A form without login fields. */
public class SearchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = Screens.column(this);
        column.addView(Screens.field(this, "Search", null, Screens.TEXT));
        setContentView(column);
    }
}

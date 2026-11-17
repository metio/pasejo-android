// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo.testapp;

import android.app.Activity;
import android.graphics.Insets;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * Builds the test APK's forms. The test APK runs in its own process without
 * the Kotlin standard library, so its components are written in Java.
 */
final class Screens {
    private Screens() {
    }

    static LinearLayout column(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return column;
    }

    static EditText field(Activity activity, String label, String hint, int inputType) {
        EditText field = new EditText(activity);
        field.setHint(label);
        field.setContentDescription(label.toLowerCase(java.util.Locale.ROOT));
        if (hint != null) {
            field.setAutofillHints(hint);
        }
        field.setInputType(inputType);
        return field;
    }

    static final int TEXT = InputType.TYPE_CLASS_TEXT;
    static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    static final String USERNAME = View.AUTOFILL_HINT_USERNAME;
    static final String PASSWORD_HINT = View.AUTOFILL_HINT_PASSWORD;
}

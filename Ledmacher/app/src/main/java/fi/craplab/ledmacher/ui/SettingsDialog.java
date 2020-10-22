/*
 * Ledmacher Android App
 *
 * Copyright (C) 2020 Sven Gregori <sven@craplab.fi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package fi.craplab.ledmacher.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import fi.craplab.ledmacher.R;

/**
 * Dialog for system settings.
 *
 * At this point that's just the IP and port of the backend. Also, it's really only an IP that can
 * be set as host, the input type won't provide a full keyboard, as this is intended as a mere
 * proof of concept that runs in a local network only. To change that behavior and have the full
 * keyboard enabled, check the {@link R.layout#dialog_settings} XML file.
 *
 * Note that the class displaying this dialog must implement the {@link SettingsChangeListener}.
 */
public class SettingsDialog extends DialogFragment {
    /**
     * Listener interface to handle changes to the settings.
     */
    public interface SettingsChangeListener {
        /**
         * Settings have changed.
         */
        void onSettingsChanged();
    }

    private SettingsChangeListener listener;
    private SharedPreferences sharedPrefs;

    /**
     * {@inheritDoc}<br><br>
     *
     * Makes sure the calling class implements the {@link SettingsChangeListener} interface and
     * sets up the {@link SharedPreferences} for later.
     *
     * @param context Context, what can I say
     * @throws ClassCastException if calling class doesn't implement {@link SettingsChangeListener}
     */
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        try {
            listener = (SettingsChangeListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement SettingsChangeListener");
        }

        sharedPrefs = context.getSharedPreferences(getString(R.string.prefs_name), Context.MODE_PRIVATE);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context context = Objects.requireNonNull(getContext());
        AlertDialog.Builder builder = new AlertDialog.Builder(Objects.requireNonNull(getActivity()));
        View rootView = getActivity().getLayoutInflater().inflate(R.layout.dialog_settings, null);

        final EditText hostText = rootView.findViewById(R.id.settings_host_edit);
        final EditText portText = rootView.findViewById(R.id.settings_port_edit);

        final String currentHost = sharedPrefs.getString(
                getString(R.string.prefs_key_backend_host),
                context.getString(R.string.settings_host_hint));

        final String currentPort = sharedPrefs.getString(
                getString(R.string.prefs_key_backend_port),
                context.getString(R.string.settings_port_hint));

        hostText.setText(currentHost);
        portText.setText(currentPort);

        builder.setView(rootView);
        builder.setTitle("Settings");
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                boolean settingsChanged = false;
                String newHost = hostText.getText().toString();
                String newPort = portText.getText().toString();

                if (!newHost.equals(currentHost)) {
                    settingsChanged = true;
                    sharedPrefs.edit().putString(
                            getString(R.string.prefs_key_backend_host),
                            hostText.getText().toString()).apply();
                }

                if (!newPort.equals(currentPort)) {
                    settingsChanged = true;
                    sharedPrefs.edit().putString(
                            getString(R.string.prefs_key_backend_port),
                            portText.getText().toString()).apply();
                }

                // don't unnecessarily invoke a listener if nothing has changed
                if (settingsChanged) {
                    listener.onSettingsChanged();
                }
            }
        });

        return builder.create();
    }
}

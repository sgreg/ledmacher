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
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import fi.craplab.ledmacher.R;

/**
 * Dialog for setting the general firmware parameters.
 *
 * Those parameters include for example the number of LEDs and delays between single colors, and
 * how fast and smooth the gradient transition is going to be.
 *
 * Note that the class displaying this dialog must implement the {@link ParametersChangedListener}
 */
public class ParameterConfigDialog extends DialogFragment {
    /**
     * Listener interface to tell that some parameters have changed.
     */
    public interface ParametersChangedListener {
        /**
         * Parameter configuration has changed.
         */
        void onParameterConfigChanged();
    }

    private ParametersChangedListener listener;
    private SharedPreferences sharedPrefs;

    /**
     * {@inheritDoc}<br><br>
     *
     * Makes sure the calling class implements the {@link ParametersChangedListener} interface and
     * sets up the {@link SharedPreferences} for later.
     *
     * @param context Context, what can I say
     * @throws ClassCastException if calling class doesn't implement {@link ParametersChangedListener}
     */
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        try {
            listener = (ParametersChangedListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement ParametersChangedListener");
        }

        sharedPrefs = context.getSharedPreferences(getString(R.string.prefs_name), Context.MODE_PRIVATE);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(Objects.requireNonNull(getActivity()));
        View rootView = getActivity().getLayoutInflater().inflate(R.layout.dialog_parameters_config, null);

        final SeekBar numLedsBar = rootView.findViewById(R.id.paramNumLeds);
        final TextView numLedsText = rootView.findViewById(R.id.paramValueNumLeds);
        final EditText waitColor = rootView.findViewById(R.id.paramValueWaitColor);
        final EditText waitGradient = rootView.findViewById(R.id.paramValueWaitGradient);
        final EditText gradientSteps = rootView.findViewById(R.id.paramValueGradientSteps);
        final CheckBox resetDevice = rootView.findViewById(R.id.paramsValueResetDevice);

        numLedsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                numLedsText.setText(String.valueOf(numLedsBar.getProgress()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // nothing do to here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // nothing to do here
            }
        });

        Resources res = getResources();

        final int numLeds = sharedPrefs.getInt(
                getString(R.string.prefs_key_num_leds),
                res.getInteger(R.integer.params_num_leds_default));
        numLedsBar.setProgress(numLeds);
        numLedsText.setText(String.valueOf(numLeds));

        waitColor.setText(String.valueOf(sharedPrefs.getInt(
                getString(R.string.prefs_key_wait_color),
                res.getInteger(R.integer.params_wait_color_default))));

        waitGradient.setText(String.valueOf(sharedPrefs.getInt(
                getString(R.string.prefs_key_wait_gradient),
                res.getInteger(R.integer.params_wait_gradient_default))));

        gradientSteps.setText(String.valueOf(sharedPrefs.getInt(
                getString(R.string.prefs_key_gradient_steps),
                res.getInteger(R.integer.params_gradient_steps_default))));

        resetDevice.setChecked(sharedPrefs.getBoolean(
                getString(R.string.prefs_key_reset_after_flash),
                res.getBoolean(R.bool.params_reset_device_default)));

        builder.setView(rootView);
        builder.setTitle("Firmware Parameters");
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                SharedPreferences.Editor editor = sharedPrefs.edit();
                editor.putInt(getString(R.string.prefs_key_num_leds), numLedsBar.getProgress());
                editor.putInt(getString(R.string.prefs_key_wait_color), Integer.parseInt(waitColor.getText().toString()));
                editor.putInt(getString(R.string.prefs_key_wait_gradient), Integer.parseInt(waitGradient.getText().toString()));
                editor.putInt(getString(R.string.prefs_key_gradient_steps), Integer.parseInt(gradientSteps.getText().toString()));
                editor.putBoolean(getString(R.string.prefs_key_reset_after_flash), resetDevice.isChecked());
                editor.apply();

                /*
                 * TODO this should kinda only invoke the listener if values were actually changed,
                 *  but there's just too many of them that I care for checking at the moment. Sorry.
                 */
                listener.onParameterConfigChanged();
            }
        });
        return builder.create();
    }
}

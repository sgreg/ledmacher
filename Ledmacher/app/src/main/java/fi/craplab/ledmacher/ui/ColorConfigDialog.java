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
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import fi.craplab.ledmacher.R;

/**
 * Color configuration dialog.
 *
 * Allows modifying and deleting a previously selected color.
 *
 * Expects two parameters in the {@code Bundle} retrieved from {@link #getArguments()}
 * <ol>
 * <li>{@code id} containing an identifier (in practice, the index in an ArrayList)</li>
 * <li>{@code color} as initial value of the modified color itself</li>
 * </ol>
 *
 * The dialog communicates the events (delete or modify) via {@link ColorConfigListener} interface
 * to the calling class, which in turn must implement that interface.
 */
public class ColorConfigDialog extends DialogFragment {
    /**
     * Listener interface to indicate changes or deletion of the color.
     */
    public interface ColorConfigListener {
        /**
         * The color configured within this dialog has changed.
         *
         * Passes the initially provided {@code id} argument back so the calling class can identify
         * which color we're talking about here, along with the new color value.
         *
         * @param id Color identifier as initially passed to the dialog
         * @param color New color value
         */
        void onColorConfigChanged(int id, int color);

        /**
         * The color configured within this dialog is meant to be deleted.
         *
         * Passes the initially provided {@code id} argument back so the calling class so the color
         * in question can be identifier.
         *
         * @param id Color identifier as initially passed to the dialog
         */
        void onColorConfigDeleted(int id);

    }
    private ColorConfigListener listener;

    private SeekBar seekBarRed;
    private SeekBar seekBarGreen;
    private SeekBar seekBarBlue;

    private TextView valueRed;
    private TextView valueGreen;
    private TextView valueBlue;

    private TextView colorCurrent;
    private TextView colorNew;

    private int red;
    private int green;
    private int blue;

    /**
     * {@inheritDoc}<br><br>
     *
     * Makes sure the calling class implements the {@link ColorConfigListener} interface.
     *
     * @param context Context, what can I say
     * @throws ClassCastException if calling class doesn't implement {@link ColorConfigListener}
     */
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        try {
            listener = (ColorConfigListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement ColorConfigListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = Objects.requireNonNull(getArguments());
        final int id = args.getInt("id", -1);
        final int color = args.getInt("color", Color.BLACK);

        if (id == -1) {
            throw new IllegalArgumentException("Requires id argument passed to dialog");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(Objects.requireNonNull(getActivity()));
        View rootView = getActivity().getLayoutInflater().inflate(R.layout.dialog_color_config, null);


        // TODO  create custom view with all the views and SeekBar

        seekBarRed = rootView.findViewById(R.id.colorConfigRed);
        valueRed = rootView.findViewById(R.id.colorConfigValueR);
        seekBarRed.setOnSeekBarChangeListener(seekBarChangeListener);

        seekBarGreen = rootView.findViewById(R.id.colorConfigGreen);
        valueGreen = rootView.findViewById(R.id.colorConfigValueG);
        seekBarGreen.setOnSeekBarChangeListener(seekBarChangeListener);

        seekBarBlue = rootView.findViewById(R.id.colorConfigBlue);
        valueBlue = rootView.findViewById(R.id.colorConfigValueB);
        seekBarBlue.setOnSeekBarChangeListener(seekBarChangeListener);

        colorCurrent = rootView.findViewById(R.id.colorConfigCurrentColor);
        colorNew = rootView.findViewById(R.id.colorConfigNewColor);

        initColorValues(color);

        builder.setView(rootView)
                .setTitle("Config Color")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        int newColor = Color.rgb(red, green, blue);
                        listener.onColorConfigChanged(id, newColor);
                        dismiss();
                    }
                })
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        listener.onColorConfigDeleted(id);
                        dismiss();
                    }
                });

        return builder.create();
    }

    private void initColorValues(int color) {
        red = Color.red(color);
        green = Color.green(color);
        blue = Color.blue(color);

        seekBarRed.setProgress(red);
        valueRed.setText(String.valueOf(red));

        seekBarGreen.setProgress(green);
        valueGreen.setText(String.valueOf(green));

        seekBarBlue.setProgress(blue);
        valueBlue.setText(String.valueOf(blue));

        colorCurrent.setBackgroundColor(color);
    }

    private final SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
            if (seekBar.equals(seekBarRed)) {
                valueRed.setText(String.valueOf(i));
                red = i;
            } else if (seekBar.equals(seekBarGreen)) {
                valueGreen.setText(String.valueOf(i));
                green = i;
            } else if (seekBar.equals(seekBarBlue)) {
                valueBlue.setText(String.valueOf(i));
                blue = i;
            }
            colorNew.setBackgroundColor(Color.rgb(red, green, blue));
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // nothing to do here
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // nothing to do here
        }
    };
}

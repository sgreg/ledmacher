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
package fi.craplab.ledmacher.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

import fi.craplab.ledmacher.R;

/**
 * Retrofit API model for firmware configuration data.
 */
public class Config {
    @SerializedName("num_leds")
    public final int numLeds;
    @SerializedName("wait_color")
    public final int waitColor;
    @SerializedName("wait_gradient")
    public final int waitGradient;
    @SerializedName("gradient_steps")
    public final int gradientSteps;
    final List<LedColor> colors;

    public static class LedColor {
        final int r;
        final int g;
        final int b;

        public LedColor(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    /**
     * Creates a new Config object.
     *
     * The preferences are taken straight from the {@link SharedPreferences} as they were set in
     * the {@link fi.craplab.ledmacher.ui.ParameterConfigDialog}, while the LED color configuration
     * is created from the given list of {@code colorValues}.
     *
     * @param context Application context, required to get access to XML resources and the app's
     *                {@link SharedPreferences}
     * @param colorValues List of color values
     */
    public Config(Context context, List<Integer> colorValues) {
        Resources res = context.getResources();
        SharedPreferences sharedPrefs = context.getSharedPreferences(context.getString(R.string.prefs_name), Context.MODE_PRIVATE);

        numLeds = sharedPrefs.getInt(context.getString(R.string.prefs_key_num_leds), res.getInteger(R.integer.params_num_leds_default));
        waitColor = sharedPrefs.getInt(context.getString(R.string.prefs_key_wait_color), res.getInteger(R.integer.params_wait_color_default));
        waitGradient = sharedPrefs.getInt(context.getString(R.string.prefs_key_wait_gradient), res.getInteger(R.integer.params_wait_gradient_default));
        gradientSteps = sharedPrefs.getInt(context.getString(R.string.prefs_key_gradient_steps), res.getInteger(R.integer.params_gradient_steps_default));
        colors = new ArrayList<>();

        for (Integer color : colorValues) {
            colors.add(new LedColor(Color.red(color), Color.green(color), Color.blue(color)));
        }
    }
}

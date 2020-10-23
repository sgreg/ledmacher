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
package fi.craplab.ledmacher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.GridLayout;
import android.widget.TextView;

import com.larswerkman.holocolorpicker.ColorPicker;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import fi.craplab.ledmacher.firmware.FirmwareHandler;
import fi.craplab.ledmacher.model.BuildResponse;
import fi.craplab.ledmacher.model.Config;
import fi.craplab.ledmacher.ui.ColorConfigDialog;
import fi.craplab.ledmacher.ui.ParameterConfigDialog;
import fi.craplab.ledmacher.ui.SettingsDialog;
import fi.craplab.ledmacher.usb.FirmwareFlashTask;
import fi.craplab.ledmacher.usb.UsbHandler;
import info.androidhive.fontawesome.FontTextView;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * The Main Activity - where everything begins, and where everything comes together.
 *
 * Literally *everything* kinda. There's way too much happening in here that possibly goes against
 * every best practices in Android architecture design, but oh well, no one should use this for any
 * serious intentions anyway.
 */
public class MainActivity extends AppCompatActivity
        implements UsbHandler.Listener, FirmwareHandler.Listener,
        SettingsDialog.SettingsChangeListener,
        ColorConfigDialog.ColorConfigListener,
        ParameterConfigDialog.ParametersChangedListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    /**
     * Retrofit instance for REST API to the backend.
     * This will be recreated whenever the backend settings (host IP or port) are changed.
     * */
    private LedmacherApi ledmacherApi;
    /** Handles the communication with the backend and gets all the firmware info and data */
    private FirmwareHandler firmwareHandler;
    /** Keeps around the initial response from the backend so we know the build hash */
    private BuildResponse buildResponse;
    /** Stores the currently selected LED color values */
    private List<Integer> colorList;
    /** Flag to keep track if there's a valid Ledmacher device attached. Can't flash without one. */
    private boolean validDeviceAttached = false;

    /** Displays all currently selected colors and lives up to its name */
    private GridLayout shittyGrid;
    /** Shows the device status, i.e. whether a valid device is connected or not */
    private TextView deviceStatusTextView;
    /** Control icon to cancel a current configuration process. */
    private FontTextView cancelIcon;
    /** Control icon to send the configuration to the backend and retrieve the firmware from it */
    private FontTextView getFirmwareIcon;
    /** Control icon the flash firmware data retrieved from the backend to the connected device */
    private FontTextView flashFirmwareIcon;

    /**
     * Internal state machine.
     *
     * States are mainly used to update the control icons.
     */
    public enum State {
        /** Reset state, everything is fresh, and nothing has happened yet. */
        RESET,
        /** First color was selected from the {@link ColorPicker} */
        FIRST_COLOR_CONFIG,
        /** Current configuration was sent to the backend, awaiting the firmware data / file now */
        CONFIG_SENT,
        /** Firmware binary data received */
        FIRMWARE_RECEIVED,
        /** Receiving firmware binary data failed */
        FIRMWARE_RECEIVE_ERROR,
        /** Flashing the firmware to the connected devices began */
        FIRMWARE_FLASH_STARTED,
        /** Firmware was flashed successfully to the device */
        FIRMWARE_FLASH_FINISHED,
        /** Something went wrong while flashing firmware to the device */
        FIRMWARE_FLASH_ERROR
    }
    /** Keeping track of the current state */
    private State state;


    /**
     * {@inheritDoc}<br><br>
     *
     * Activity is created - let there be light ..and all the UI elements.
     *
     * Sets up pretty much everything on the UI and all the handlers.
     *
     * @param savedInstanceState saved instance state of the Activity
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        colorList = new ArrayList<>();
        setupBackendApi();
        firmwareHandler = new FirmwareHandler(MainActivity.this);

        findViewById(R.id.settingsIcon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SettingsDialog dialog = new SettingsDialog();
                dialog.show(getSupportFragmentManager(), "settingsDialog");
            }
        });

        findViewById(R.id.parametersIcon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ParameterConfigDialog dialog = new ParameterConfigDialog();
                dialog.show(getSupportFragmentManager(), "parametersDialog");
            }
        });

        ColorPicker colorPicker = findViewById(R.id.picker);
        colorPicker.setShowOldCenterColor(false);
        colorPicker.setOnColorSelectedListener(new ColorPicker.OnColorSelectedListener() {
            @Override
            public void onColorSelected(final int color) {
                Log.d(TAG, "color selected: " + String.format("0x%08x", color));
                if (colorList.isEmpty()) {
                    setState(State.FIRST_COLOR_CONFIG);
                }
                colorList.add(color);
                final int id = colorList.size() - 1;
                shittyGrid.addView(createColorView(id, color));
            }
        });

        shittyGrid = findViewById(R.id.shitty_grid);

        cancelIcon = findViewById(R.id.cancelIcon);
        cancelIcon.setOnClickListener(cancelIconClickListener);

        getFirmwareIcon = findViewById(R.id.getFirmwareIcon);
        getFirmwareIcon.setOnClickListener(getFirmwareIconClickListener);

        flashFirmwareIcon = findViewById(R.id.flashFirmwareIcon);
        flashFirmwareIcon.setOnClickListener(flashFirmwareIconClickListener);

        deviceStatusTextView = findViewById(R.id.deviceStatus);
        setDeviceStatusView(getString(R.string.device_status_none));

        setState(State.RESET);

        UsbHandler usbHandler = UsbHandler.getInstance(this);
        usbHandler.addListener(this);
        usbHandler.checkForDevice();
    }

    /**
     *{@inheritDoc}<br><br>
     *
     * Activity gets resumed.
     *
     * We're active, so register ourselves as listener to the {@link UsbHandler}.
     */
    @Override
    protected void onResume() {
        super.onResume();
        UsbHandler usbHandler = UsbHandler.getInstance(this);
        usbHandler.addListener(this);
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Activity is paused.
     *
     * Free all resources allocated in the {@link #onResume()} method, which in this case is
     * registering ourselves to the {@link UsbHandler} as listener.
     */
    @Override
    protected void onPause() {
        UsbHandler usbHandler = UsbHandler.getInstance();
        usbHandler.removeListener(this);
        super.onPause();
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Activity gets destroyed.
     *
     * Free all resources allocated in the {@link #onCreate(Bundle)} method, which in this case
     * is the {@link UsbHandler} singleton instance. We're practically exiting, so no need for
     * that one anyway anymore.
     */
    @Override
    protected void onDestroy() {
        UsbHandler.destroyInstance();
        super.onDestroy();
    }

    /**
     * Settings configured in the {@link SettingsDialog} have changed.
     *
     * As those settings are currently just the IP and host of the backend, either one of that has
     * changed, so rebuild the Retrofit {@link LedmacherApi} instance with the new information.
     *
     * With a new backend address in place, the get firmware icon should probably be reset to some
     * state that allows re-requesting the firmware, so check the internal {@link State} and unless
     * it's {@link State#RESET} or {@link State#FIRST_COLOR_CONFIG} (i.e. any state after the
     * firmware was requested) set it back to {@link State#FIRST_COLOR_CONFIG}, adjusting the flash
     * firmware icon along the way.
     */
    @Override
    public void onSettingsChanged() {
        setupBackendApi();
        switch (state) {
            case RESET:
            case FIRST_COLOR_CONFIG:
                // do nothing, we're good
                break;
            default:
                setState(State.FIRST_COLOR_CONFIG);
                handleFlashFirmwareIcon();
        }
    }

    /**
     * Firmware parameters configured in the {@link ParameterConfigDialog} have changed.
     *
     * If we already have a valid firmware, we probably want to rebuild a new one now, so handle
     * the {@link State} and flash firmware icon accordingly. On the other hand, if no firmware
     * build was requested yet, we don't need to bother with that.
     */
    @Override
    public void onParameterConfigChanged() {
        switch (state) {
            case RESET:
            case FIRST_COLOR_CONFIG:
                // do nothing, we're good
                break;
            default:
                setState(State.FIRST_COLOR_CONFIG);
                handleFlashFirmwareIcon();
        }
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Checks if the device is a valid {@link UsbHandler.DeviceType#LEDMACHER_BOOTLOADER} device
     * and sets the {@link #deviceStatusTextView} content and color accordingly.
     *
     * This also checks the current {@link State} in order to enable the {@link #flashFirmwareIcon}
     * if necessary / possible.
     *
     * @param deviceType {@link UsbHandler.DeviceType} of the freshly attached USB device
     */
    @Override
    public void onDeviceAttached(UsbHandler.DeviceType deviceType) {
        boolean validDevice = false;
        String deviceText = deviceType.name();

        if (deviceType == UsbHandler.DeviceType.LEDMACHER_BOOTLOADER) {
            validDevice = true;
            deviceText = getString(R.string.device_status_ledmacher);
        }

        validDeviceAttached = validDevice;
        setDeviceStatusView(deviceText);
        handleFlashFirmwareIcon();
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Sets the {@link #deviceStatusTextView} content and background to the fact that there ain't
     * no device no more.
     *
     * Also handles the {@link #flashFirmwareIcon} situation accordingly.
     */
    @Override
    public void onDeviceDetached() {
        validDeviceAttached = false;
        setDeviceStatusView(getString(R.string.device_status_removed));
        handleFlashFirmwareIcon();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                setDeviceStatusView(getString(R.string.device_status_none));
            }
        }, 5000);
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * If there's a valid {@link BuildResponse} hash stored internally, continue to request the
     * firmware information from the backend, which is then handed back to {@link FirmwareHandler}
     * for handling.
     *
     * @throws IllegalStateException if no firmware build hash is available
     */
    @Override
    public void onFirmwareInformationReceived() {
        if (buildResponse == null) {
            throw new IllegalStateException("Got no hash");
        }
        ledmacherApi.getFirmwareBinary(buildResponse.hash)
                .enqueue(firmwareHandler.firmwareBytesCallback);
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Set internal {@link State} accordingly.
     */
    @Override
    public void onFirmwareValidated() {
        setState(State.FIRMWARE_RECEIVED);
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Set internal {@link State} accordingly.
     */
    @Override
    public void onFirmwareHandlingError() {
        setState(State.FIRMWARE_RECEIVE_ERROR);
    }

    /**
     * Retrofit {@link Callback} handler for {@link LedmacherApi#buildNewFirmware(Config)}.
     */
    private final Callback<BuildResponse> firmwareBuildCallback = new Callback<BuildResponse>() {
        /**
         * {@inheritDoc}<br><br>
         *
         * API call succeeded, returning the {@link BuildResponse} containing the freshly built
         * firmware's build hash, or {@code null} if something went wrong. Set the {@link State}
         * accordingly, and if all went well, continue by requesting the firmware information from
         * the backend. The response to that is then handled by the {@link FirmwareHandler}.
         *
         * @param call The original {@link Call} that sent the request
         * @param response {@link Response} from the backend containing the {@link BuildResponse}
         */
        @Override
        public void onResponse(@NonNull Call<BuildResponse> call, Response<BuildResponse> response) {
            buildResponse = response.body();

            if (buildResponse == null) {
                Log.e(TAG, "Failed to build firmware: " + response);
                setState(State.FIRMWARE_RECEIVE_ERROR);
            } else {
                Log.d(TAG, "Got response: " + buildResponse);
                ledmacherApi.getFirmwareInformation(buildResponse.hash)
                        .enqueue(firmwareHandler.firmwareInformationCallback);
            }
        }

        /**
         * {@inheritDoc}<br><br>
         *
         * API call failed, no {@link BuildResponse} was received and therefore no firmware was
         * built, or at least none that we can use. Set {@link State} accordingly.
         *
         * @param call The original {@link Call} that sent the request
         * @param t Want went wrong
         */
        @Override
        public void onFailure(@NonNull Call<BuildResponse> call, @NonNull Throwable t) {
            Log.e(TAG, "Something when wrong", t);
            buildResponse = null;
            setState(State.FIRMWARE_RECEIVE_ERROR);
        }
    };

    /**
     * Set up the Retrofit backend API object.
     *
     * Reads the IP and port settings from the {@link SharedPreferences} and creates the Retrofit
     * object from it.
     */
    private void setupBackendApi() {
        SharedPreferences sharedPrefs = getSharedPreferences(
                getString(R.string.prefs_name), Context.MODE_PRIVATE);

        String host = sharedPrefs.getString(
                getString(R.string.prefs_key_backend_host),
                getString(R.string.settings_host_hint));
        String port = sharedPrefs.getString(
                getString(R.string.prefs_key_backend_port),
                getString(R.string.settings_port_hint));

        String baseUrl = String.format(getString(R.string.backend_base_url), host, port);
        Log.d(TAG, "Setting backend host: " + baseUrl);

        final Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ledmacherApi = retrofit.create(LedmacherApi.class);
    }

    /**
     * Create a {@link TextView} rectangle with the given {@code color} as background.
     *
     * This is currently used to display a selected color in the color configuration grid view.
     * It certainly ain't a pretty way, but here we are.
     *
     * @param id Color id, e.g. ArrayList index
     * @param color Value of the color itself
     * @return A view ready to be attached to the color configuration grid view
     */
    private View createColorView(final int id, final int color) {
        TextView colorView = new TextView(this);
        colorView.setWidth(80);
        colorView.setHeight(80);
        colorView.setBackgroundColor(color);
        colorView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ColorConfigDialog dialog = new ColorConfigDialog();
                Bundle args = new Bundle();
                args.putInt("id", id);
                args.putInt("color", color);
                dialog.setArguments(args);
                dialog.show(getSupportFragmentManager(), "settingsDialog");
            }
        });

        return colorView;
    }

    /**
     * Rebuild the color configuration view grid. Poorly.
     *
     * Just removes everything from the view itself and repopulates it with newly created views.
     * There most definitely has to be a better way than this, but here we are. Gotta keep living
     * up to the name..
     */
    private void rebuildShittyGrid() {
        // oh there most definitely has to be a better way than this.. shitty grid for sure.
        shittyGrid.removeAllViews();
        int i = 0;
        for (int color : colorList) {
            shittyGrid.addView(createColorView(i++, color));
        }
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Update the color of the given {@code id} (i.e. {@link ArrayList} index) in the internal
     * {@link #colorList} to the given {@code color} value. Update the color configuration grid
     * view afterwards.
     *
     * @param id Color identifier as initially passed to the dialog
     * @param color New color value
     */
    @Override
    public void onColorConfigChanged(int id, int color) {
        Log.d(TAG, "color " + id + " changed from "
                + Integer.toHexString(colorList.get(id)) + " to " + Integer.toHexString(color));
        colorList.set(id, color);
        rebuildShittyGrid();
    }

    /**
     * {@inheritDoc}<br><br>
     *
     * Remove the color with the given {@code id} (i.e. {@link ArrayList} index) from the internal
     * {@link #colorList} and update the color configuration grid view.
     *
     * @param id Color identifier as initially passed to the dialog
     */
    @Override
    public void onColorConfigDeleted(int id) {
        Log.d(TAG, "color " + id + " (" + Integer.toHexString(colorList.get(id)) + ") deleted");
        colorList.remove(id);
        if (colorList.isEmpty()) {
            // last color removed, get back to reset state
            setState(State.RESET);
        }
        rebuildShittyGrid();
    }

    /**
     * Set the internal state to the given {@code newState}.
     *
     * This will perform all necessary UI updates associated to the new state, especially what comes
     * to the control icons to cancel / get firmware / flash firmware.
     *
     * For example, in {@link State#RESET} state, none of the icons should be enabled as nothing can
     * be really performed yet (there's no color configured, therefore no firmware can be requested
     * and cancelling won't do anything here either). On the other hand, in states like for example
     * {@link State#FIRMWARE_RECEIVE_ERROR}, the error should be indicated on the icons themselves.
     *
     * If the given {@code newState} is the same the current state, the call is essentially ignored
     * (i.e. nothing is going to happen)
     *
     * @param newState new internal state
     */
    public void setState(State newState) {
        if (newState == state) {
            return;
        }

        Log.d(TAG, "Setting state " + state + " -> " + newState);
        state = newState;

        switch (newState) {
            case RESET:
                /*
                 * Reset all icons to disabled, greyed out state.
                 * This is either the initial state on startup, or after a cancel operation.
                 */
                cancelIcon.setTextColor(getColor(R.color.controlIconDisabled));
                cancelIcon.setEnabled(false);
                getFirmwareIcon.setTextColor(getColor(R.color.controlIconDisabled));
                getFirmwareIcon.setEnabled(false);
                flashFirmwareIcon.setTextColor(getColor(R.color.controlIconDisabled));
                flashFirmwareIcon.setEnabled(false);
                break;

            case FIRST_COLOR_CONFIG:
                /*
                 * First color was configured, enable both the cancel icon and get firmware icon.
                 */
                cancelIcon.setTextColor(getColor(R.color.cancelIconEnabled));
                cancelIcon.setEnabled(true);
                getFirmwareIcon.setTextColor(getColor(R.color.controlIconEnabled));
                getFirmwareIcon.setEnabled(true);
                break;

            case CONFIG_SENT:
                /*
                 * Config was sent to the backend. Disable the get firmware button to avoid multiple
                 * unnecessary calls for the same configuration, but make it blink until the all
                 * backend communication is done and the firmware was received.
                 */
                getFirmwareIcon.setEnabled(false);
                progressBlinkAnimation(getFirmwareIcon);
                break;

            case FIRMWARE_RECEIVED:
                /*
                 * Firmware was successfully received, stop the animation and show success state
                 * by turning the get firmware icon green. Flash icon could now be enabled, but
                 * that also depends whether a valid Ledmacher device is connected via USB.
                 */
                getFirmwareIcon.clearAnimation();
                getFirmwareIcon.setTextColor(getColor(R.color.getFirmwareIconOkay));
                handleFlashFirmwareIcon();
                break;

            case FIRMWARE_RECEIVE_ERROR:
                /*
                 * Retrieving firmware failed, stop the progress animation and show failure state
                 * by turning the get firmware icon red. Re-enable the button itself so another
                 * request can be send.
                 */
                getFirmwareIcon.clearAnimation();
                getFirmwareIcon.setTextColor(getColor(R.color.getFirmwareIconError));
                getFirmwareIcon.setEnabled(true);
                break;

            case FIRMWARE_FLASH_STARTED:
                /*
                 * Flashing firmware to the device started. Disable the flash firmware icon to avoid
                 * another call while flashing is already ongoing, but make it blink to show that
                 * something is happening.
                 */
                flashFirmwareIcon.setEnabled(false);
                progressBlinkAnimation(flashFirmwareIcon);
                break;

            case FIRMWARE_FLASH_FINISHED:
                /*
                 * Firmware flashing succeeded, stop the animation and show green success state as
                 * icon. Keep clicking on the icon itself disabled for now, the rest will be handled
                 * in the handleFlashFirmwareIcon(), which starts a timer to change the state
                 * automatically back to FIRMWARE_RECEIVED, but also considers if the device is
                 * actually still connected and wasn't automatically reset.
                 */
                flashFirmwareIcon.clearAnimation();
                flashFirmwareIcon.setTextColor(getColor(R.color.flashFirmwareIconOkay));
                flashFirmwareIcon.setEnabled(false);
                handleFlashFirmwareIcon();
                break;

            case FIRMWARE_FLASH_ERROR:
                /*
                 * Flashing firmware failed, stop the animation and show red fail status as icon.
                 * Icon itself is enabled again to allow another attempt at flashing, and everything
                 * else is handled by handleFlashFirmwareIcon() again.
                 */
                flashFirmwareIcon.clearAnimation();
                flashFirmwareIcon.setTextColor(getColor(R.color.flashFirmwareIconError));
                flashFirmwareIcon.setEnabled(true);
                handleFlashFirmwareIcon();
                break;
        }
    }

    /**
     * Handle the flash firmware icon in addition to the {@link #setState(State)} method.
     *
     * Since flashing the firmware depends on both the correct internal {@link State} but also that
     * a valid Ledmacher device is connected to USB, things are sorted out here accordingly.
     */
    private void handleFlashFirmwareIcon() {
        Log.d(TAG, "handle firmware at state " + state + " and valid " + validDeviceAttached);
        if (state == State.FIRMWARE_RECEIVED && validDeviceAttached) {
            flashFirmwareIcon.setTextColor(getColor(R.color.controlIconEnabled));
            flashFirmwareIcon.setEnabled(true);
        } else if (state == State.FIRMWARE_FLASH_FINISHED || state == State.FIRMWARE_FLASH_ERROR) {
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setState(State.FIRMWARE_RECEIVED);
                }
            }, 5000);
        } else {
            flashFirmwareIcon.setTextColor(getColor(R.color.controlIconDisabled));
            flashFirmwareIcon.setEnabled(false);
        }
    }

    /**
     * Create and return a new {@link Animation} and attach it to the given {@code view}.
     *
     * This creates a blinking animation via {@link AlphaAnimation}.
     *
     * @param view the view to attach the freshly created {@link AlphaAnimation} to
     */
    private void progressBlinkAnimation(TextView view) {
        Animation anim = new AlphaAnimation(0.0f, 1.0f);
        anim.setDuration(100);
        anim.setStartOffset(50);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(Animation.INFINITE);

        view.setTextColor(getColor(R.color.controlIconEnabled));
        view.startAnimation(anim);
    }

    /**
     * Set the device status text to the given {@code text} string.
     *
     * This also checks the current {@link #validDeviceAttached} status (i.e. is a valid Ledmacher
     * device connected via USB) and sets the background color accordingly
     *
     * @param text text string to display on the device status bar
     */
    public void setDeviceStatusView(String text) {
        if (validDeviceAttached) {
            deviceStatusTextView.setBackgroundColor(getColor(R.color.deviceStatusValid));
        } else {
            deviceStatusTextView.setBackgroundColor(getColor(R.color.deviceStatusInvalid));
        }
        deviceStatusTextView.setText(text);
    }

    /**
     * Makes the magic happen when clicking on the cancel icon.
     *
     * Clears all the previously configured colors and gives the app a fresh start.
     *
     * Since the {@link #setState(State)} method keeps track of enabled and disabling clicking on
     * the icon itself, there's no further check performed in here
     */
    private final View.OnClickListener cancelIconClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            colorList.clear();
            shittyGrid.removeAllViews();
            setState(State.RESET);
        }
    };

    /**
     * Makes the magic happen when clicking on the get firmware icon.
     *
     * Creates a {@link Config} object from the currently configured list of colors, along with
     * common firmware values defined in the app's {@link SharedPreferences}, and sends a request
     * to build a firmware from all that to the backend.
     *
     * Whatever the backend retrieves is then handled in {@link #firmwareBuildCallback}.
     *
     * Since the {@link #setState(State)} method keeps track of enabled and disabling clicking on
     * the icon itself, there's no further check performed in here
     */
    private final View.OnClickListener getFirmwareIconClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Config config = new Config(MainActivity.this, colorList);
            ledmacherApi.buildNewFirmware(config).enqueue(firmwareBuildCallback);
            setState(State.CONFIG_SENT);
        }
    };

    /**
     * Makes the magic happen when clicking on the flash firmware icon.
     *
     * Starts a mew {@link FirmwareFlashTask} with the data retrieved from the backend and stored
     * in the {@link #firmwareHandler} object.
     *
     * Since the {@link #setState(State)} method keeps track of enabled and disabling clicking on
     * the icon itself, there's no further check performed in here
     */
    private final View.OnClickListener flashFirmwareIconClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            setState(State.FIRMWARE_FLASH_STARTED);
            new FirmwareFlashTask(MainActivity.this).execute(firmwareHandler);
        }
    };
}

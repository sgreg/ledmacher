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
package fi.craplab.ledmacher.firmware;

import android.util.Log;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import androidx.annotation.NonNull;
import fi.craplab.ledmacher.model.FirmwareData;
import fi.craplab.ledmacher.usb.UsbHandler;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Handles everything related to the Ledmacher application firmware.
 *
 * Retrieves the information and raw data of the firmware from the backend, and performs basic
 * validations to make sure everything was retrieved correctly (by comparing the size and SHA1
 * checksum of the binary data sent as part of the firmware information and calculating it for
 * the actual firmware bytes). The firmware bytes are stored internally and later passed on to
 * the {@link fi.craplab.ledmacher.usb.FirmwareFlashTask} to write them to the actual device.
 */
public class FirmwareHandler {
    private static final String TAG = FirmwareHandler.class.getSimpleName();

    /**
     * Listener interface for all firmware handling related events.
     */
    public interface Listener {
        /**
         * Firmware information was received from the backend.
         *
         * This essentially reports that executing
         * {@link fi.craplab.ledmacher.LedmacherApi#getFirmwareInformation(String)} succeeded.
         */
        void onFirmwareInformationReceived();

        /**
         * Firmware information was successfully validated, everything is ready to flash it to
         * the device now.
         */
        void onFirmwareValidated();

        /**
         * Something went wrong along the way of handling the firmware.
         *
         * This could have several reasons, like one of the calls to the backend failed, or the
         * retrieved data could not get validated.
         *
         * This could be extended to send an actual reason along as parameter.
         */
        void onFirmwareHandlingError();
    }

    /** The Listener receiving all the fresh gossip about the firmware states */
    private final Listener listener;
    /** Last {@link FirmwareData} received from the backend */
    private FirmwareData firmwareData;
    /** The raw bytes of the firmware itself, once received from the backend */
    private byte[] firmware;
    /** Keeps track internally whether the firmware has already been successfully validated */
    private boolean validated;
    /** Size of the firmware data in number of memory pages */
    private int numberOfPages;

    /**
     * Create a new {@code FirmwareHandler} with the given {@code listener}.
     *
     * @param listener Listener interface for snitching on the firmware status
     */
    public FirmwareHandler(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * Check if a firmware was retrieved from the backend and whether it's validated.
     *
     * @return {@code true} if there's valid firmware available, {@code false} if there isn't
     */
    public boolean isValidated() {
        return validated;
    }

    /**
     * Retrieve those raw juicy bytes of firmware, or {@code null} if there's no valid one present.
     *
     * @return Firmware bytes if present and successfully validated, {@code null} otherwise
     */
    public byte[] getFirmware() {
        return validated ? firmware : null;
    }

    /**
     * Get the firmware size in terms of memory pages.
     *
     * When flashing the firmware to the device, the firmware is split into chunks of a specific
     * size that represents the microcontroller's memory layout. In case of AVR, that's a memory
     * page of 128 bytes. So this method returns how many of those memory pages are going to be
     * used (and therefore flashed) for this specific firmware.
     *
     * This requires a validated firmware.
     *
     * @return Size of the firmware in umber of 128 byte sized memory pages if a validated firmware
     * is present, {@code 0} otherwise
     */
    public int getNumberOfPages() {
        return validated ? numberOfPages : 0;
    }

    /**
     * Get the firmware size in bytes.
     *
     * This requires a validated firmware.
     *
     * @return Firmware size in bytes if a validated firmware is present, {@code 0} otherwise
     */
    public int getTotalSize() {
        return validated ? firmwareData.size : 0;
    }

    /**
     * Retrofit {@link Callback} handler for
     * {@link fi.craplab.ledmacher.LedmacherApi#getFirmwareInformation(String)}.
     */
    public final Callback<FirmwareData> firmwareInformationCallback = new Callback<FirmwareData>() {
        /**
         * {@inheritDoc}<br><br>
         *
         * API call succeeded, returning the {@link FirmwareData} containing all the juice details
         * about the requested firmware build, or {@code null} if something went wrong.
         *
         * Notify {@link Listener} that the information has been received, which then continues
         * to request the actual firmware data. An improved alternative would skip the listener and
         * send the request in here, but that would require a bit of refactoring the way the
         * {@link fi.craplab.ledmacher.LedmacherApi} is handled (which at this point is coupled
         * straight to the {@link fi.craplab.ledmacher.MainActivity}).
         *
         * It also checks if the firmware can be validated, and if so does, but at this point,
         * that's unlikely to happen as the binary firmware data itself hasn't even been requested
         * yet. However, that could change at some point in the future (who knows, it's 2020,
         * anything can happen), so the code is ready for it already now. Hmm.
         *
         * @param call The original {@link Call} that sent the request
         * @param response {@link Response} from the backend containing the {@link FirmwareData}
         */
        @Override
        public void onResponse(@NonNull Call<FirmwareData> call, @NonNull Response<FirmwareData> response) {
            FirmwareData data = response.body();

            if (data == null) {
                Log.e(TAG, "Failed to get firmware information: " + response);
                listener.onFirmwareHandlingError();
            } else {
                Log.d(TAG, "Firmware Data: " + data);
                firmwareData = data;
                listener.onFirmwareInformationReceived();
                validateIfReady();
            }
        }

        /**
         * {@inheritDoc}<br><br>
         *
         * API call failed, no {@link FirmwareData} was received. Inform the listeners.
         *
         * @param call The original {@link Call} that sent the request
         * @param t Want went wrong
         */
        @Override
        public void onFailure(@NonNull Call<FirmwareData> call, @NonNull Throwable t) {
            Log.e(TAG, "Getting firmware information failed", t);
            FirmwareHandler.this.firmwareData = null;
            listener.onFirmwareHandlingError();
        }
    };

    /**
     * Retrofit {@link Callback} handler for
     * {@link fi.craplab.ledmacher.LedmacherApi#getFirmwareBinary(String)}.
     */
    public final Callback<ResponseBody> firmwareBytesCallback = new Callback<ResponseBody>() {
        /**
         * {@inheritDoc} <br><br>
         *
         * API call succeeded, returning the raw binary firmware data inside the {@link Response},
         * or {@code null} if something went wrong. If we have firmware data, extract it and start
         * the validation to make sure all went well. If there's no firmware data, inform the
         * listener about it.
         *
         * @param call The original {@link Call} that sent the request
         * @param response {@link Response} from the backend containing the raw firmware data
         */
        @Override
        public void onResponse(@NonNull Call<ResponseBody> call,
                @NonNull Response<ResponseBody> response) {

            if (response.body() != null) {
                try {
                    firmware = response.body().bytes();
                    Log.d(TAG, "Got them bytes, " + firmware.length + " of them");
                    validateIfReady();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to retrieve bytes from body", e);
                    FirmwareHandler.this.firmware = null;
                    listener.onFirmwareHandlingError();
                }
            }
        }

        /**
         * {@inheritDoc}<br><br>
         *
         * API call failed, no firmware binary was received. Inform the listeners.
         *
         * @param call The original {@link Call} that sent the request
         * @param t Want went wrong
         */
        @Override
        public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
            Log.e(TAG, "Getting firmware bytes failed", t);
            FirmwareHandler.this.firmware = null;
            listener.onFirmwareHandlingError();
        }
    };

    /**
     * Check whether the firmware is ready to be validated.
     *
     * In order to validate the firmware, both the firmware information and the raw firmware bytes
     * must have been received from the backend server. This method just tells that both of those
     * pieces are actually available.
     *
     * @return {@code true} if the firmware is ready to be validated, {@code false} if at least one
     * of the firmware information or bytes are missing
     */
    private boolean canValidate() {
        return (firmwareData != null && firmware != null);
    }

    /**
     * Check if the firmware is ready to be validate, and go ahead with it if it is,
     *
     * Reports the status of the validation via the {@link Listener} interface.
     *
     * @see #canValidate()
     */
    private void validateIfReady() {
        if (canValidate()) {
            if (validate()) {
                listener.onFirmwareValidated();
            } else {
                listener.onFirmwareHandlingError();
            }
        }
    }

    /**
     * Perform the firmware validation and report its result back.
     *
     * When retrieving the firmware information from the backend, the firmware's size in bytes
     * as well as a SHA1 checksum of the firmware binary bytes is contained in that information.
     * The method compares those values with those of the actual firmware bytes received,
     * including recalculating the checksum. If all matches, all is good and the firmware is
     * considered valid - hooray.
     *
     * Once validated, the number of memory page (see (@link {@link #getNumberOfPages()}} is
     * calculated as well.
     *
     * @return {@code true} if firmware was successfully validated, {@code false} otherwise
     */
    private boolean validate() {
        if (!canValidate()) {
            return false;
        }
        if (validated) {
            return true;
        }

        validated = false;

        int claimedSize = firmwareData.size;
        int actualSize = firmware.length;
        boolean validSize = (claimedSize == actualSize);

        Log.d(TAG, "checking size is okay");
        Log.d(TAG, "claimed: " + claimedSize);
        Log.d(TAG, "actual:  " + actualSize);
        Log.d(TAG, "valid:   " + validSize);

        if (!validSize) {
            return false;
        }

        byte[] claimedChecksum = hexStringToByteArray(firmwareData.checksum);
        byte[] actualChecksum;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            actualChecksum = digest.digest(firmware);
        } catch (NoSuchAlgorithmException e) {
            /*
             * TODO rare case I guess, monitor if it really happens. If it does, show warning
             *  "cannot validate data, proceed at own risk"
             */
            e.printStackTrace();
            return false;
        }

        boolean validChecksum = MessageDigest.isEqual(claimedChecksum, actualChecksum);

        Log.d(TAG, "checking size is okay");
        Log.d(TAG, "claimed: " + byteArrayToHexString(claimedChecksum));
        Log.d(TAG, "actual:  " + byteArrayToHexString(actualChecksum));
        Log.d(TAG, "valid:   " + validChecksum);

        if (!validChecksum) {
            return false;
        }

        numberOfPages = (int) Math.ceil(actualSize / (double) UsbHandler.PAGE_SIZE);
        Log.d(TAG, "that's " + numberOfPages + " pages");

        validated = true;
        return true;
    }

    /**
     * Convert a given string of hex values into a byte array.
     *
     * If the given {@code input} is not a series of consecutive hex values (as one would find it
     * in a SHA1 checksum, as that's what this was primary meant for), you're on your own and funky
     * things are bound to happen ..maybe. Exceptions are the rule?
     *
     * @param input Hex string to convert into a byte array
     * @return Byte array containing the input string as byte array
     */
    private static byte[] hexStringToByteArray(String input) {
        int inputLen = input.length();
        byte[] outputData = new byte[inputLen / 2];

        for (int i = 0; i < inputLen; i += 2) {
            outputData[i / 2] = (byte) ((Character.digit(input.charAt(i), 16) << 4)
                    + Character.digit(input.charAt(i + 1), 16));
        }

        return outputData;
    }

    /** Make life easier for converting byte to hex string */
    private static final char[] hexArray = "0123456789ABCDEF".toCharArray();

    /**
     * Turn a given byte array into a hex value string.
     *
     * Basically the opposite of what {@link #hexStringToByteArray(String)} does, and in that sense,
     * this is used to convert a {@link MessageDigest} checksum (which deals in byte arrays) into
     * a hex value as one would find in a checksum.
     *
     * @param bytes Byte array to convert into hex String
     * @return Hex string of the input array
     */
    private static String byteArrayToHexString(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}

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
package fi.craplab.ledmacher.usb;

import android.os.AsyncTask;
import android.util.Log;

import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import fi.craplab.ledmacher.MainActivity;
import fi.craplab.ledmacher.firmware.FirmwareHandler;

/**
 * AsyncTask to flash the binary firmware data to a Ledmacher device connected via USB.
 */
public class FirmwareFlashTask extends AsyncTask<FirmwareHandler, Integer, Boolean> {

    /**
     * A weak reference to the MainActivity to directly update the state (which in turn updates
     * the UI components involved) as the flash process progresses.
     *
     * This could also use some listener interface instead.
     */
    private final WeakReference<MainActivity> activityReference;

    /**
     * Create a new {@code FirmwareFlashTask} instance with the given {@code activity}.
     *
     * @param activity Activity with all the UI components
     */
    public FirmwareFlashTask(@NonNull MainActivity activity) {
        activityReference = new WeakReference<>(activity);
    }

    /**
     * Called before the actual firmware flash task starts, i.e. before
     * {@link #doInBackground(FirmwareHandler...)} is called.
     *
     * Sets the {@link MainActivity.State} accordingly.
     */
    @Override
    protected void onPreExecute() {
        activityReference.get().setState(MainActivity.State.FIRMWARE_FLASH_STARTED);
    }

    /**
     * Called after the flashing process, i.e. after {@link #doInBackground(FirmwareHandler...)}
     * finished.
     *
     * Passes the execution {@code success} along as parameter, which then determines the new
     * {@link MainActivity.State} that is set.
     *
     * @param success {@code true} if firmware flashing succeeded, {@code false} in case of an error
     */
    @Override
    protected void onPostExecute(Boolean success) {
        MainActivity.State state = (!success) ? MainActivity.State.FIRMWARE_FLASH_ERROR
                                              : MainActivity.State.FIRMWARE_FLASH_FINISHED;
        activityReference.get().setState(state);
    }

    /**
     * Publish updated flash progress on UI thread.
     *
     * Gets called whenever {@link #publishProgress(Integer...)} is called inside the
     * {@link #doInBackground(FirmwareHandler...)} method and receives how many percent the flash
     * process has progressed.
     *
     * This can be used to show the progress information in the UI as it runs within the UI thread,
     * unlike {@link #doInBackground(FirmwareHandler...)} itself. In an earlier version, there was
     * a progress bar on the UI, but now it's just a blinking icon. Progress bar (or any other form
     * of percentage indication) might be a good idea though, so even though the method does
     * absolutely nothing at this point, it serves as a reminder to add a progress indicator.
     *
     * @param values Absolute value of flash progress percentage
     */
    @Override
    protected void onProgressUpdate(Integer... values) {
        // Nothing to do for the time being. This could have a nice progress bar at some point[TM]
    }

    /**
     * Perform the actual firmware flashing task.
     *
     * @param handlers {@link FirmwareHandler} containing all required details about the firmware
     * @return {@code true} if flashing finished successfully, {@code false} if it didn't
     */
    @Override
    protected Boolean doInBackground(FirmwareHandler... handlers) {
        FirmwareHandler firmwareHandler = handlers[0];

        if (!firmwareHandler.isValidated()) {
            return false;
        }

        try {
            UsbHandler usbHandler = UsbHandler.getInstance();
            usbHandler.initiateFirmwareFlash(firmwareHandler.getNumberOfPages());

            double progressPerPage = 100.0 / firmwareHandler.getNumberOfPages();
            byte[] buffer = new byte[UsbHandler.HEADER_SIZE + UsbHandler.PAGE_SIZE];
            byte[] firmware = firmwareHandler.getFirmware();
            int offset = 0;

            for (int pageNumber = 1;
                 pageNumber <= firmwareHandler.getNumberOfPages(); pageNumber++) {
                int bytesLeft = firmwareHandler.getTotalSize() - offset;
                int chunkSize = Math.min(bytesLeft, UsbHandler.PAGE_SIZE);

                buffer[0] = (byte) pageNumber;
                buffer[1] = (byte) chunkSize;
                System.arraycopy(firmware, offset, buffer, UsbHandler.HEADER_SIZE, chunkSize);

                int retryCount = usbHandler.flashFirmwarePage(buffer, chunkSize + UsbHandler.HEADER_SIZE);
                Log.d("TAG", "transferred page " + pageNumber + " bytes " + offset + "-" +
                        (offset + chunkSize - 1) + " after " + retryCount + " retries");
                publishProgress((int) (pageNumber * progressPerPage));

                offset += chunkSize;
            }

            usbHandler.finalizeFirmwareFlash();
        } catch (Exception e) {
            return false;
        }
        return true;
    }
}
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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import fi.craplab.ledmacher.R;
import fi.craplab.ledmacher.firmware.FirmwareHandler;

/**
 * Handles everything USB.
 *
 * This is a singleton.
 * And this could really use a bit of splitting things up into multiple classes..
 */
public class UsbHandler {
    private static final String TAG = UsbHandler.class.getSimpleName();

    /** Expected USB Vendor ID for the Ledmacher Bootloader device */
    private static final int CRAPLAB_VID = 0x1209;
    /** Expected USB Product ID for the Ledmacher Bootloader device */
    private static final int RUDY_PID = 0xb00b;

    /** Expected USB product name string for the Ledmacher Bootloader device */
    private static final String BOOTLOADER_PRODUCT_NAME = "RUDY";
    /** Expected USB serial number string for the Ledmacher Bootloader device */
    private static final String BOOTLOADER_SERIAL_NUMBER = "Ledmacher";
    /** Expected banner string retrieved from the bootloader's HELLO command */
    private static final String BOOTLOADER_BANNER_PREFIX = "Ledmacher Bootloader ";

    /** Memory page size that is transferred and written at once */
    public static final int PAGE_SIZE = 128;
    /**
     * Chunk transfer header size in bytes.
     * Transferring the firmware to the device is done in chunks if {@value PAGE_SIZE} bytes,
     * along with a header containing information on the memory page number currently transferred,
     * and the actual size of the data within that chunk. Both are an 8-bit value each, hence the
     * header size is 2.
     */
    static final int HEADER_SIZE = 2;

    /** USB control transfer timeout in milliseconds */
    private static final int USB_TIMEOUT_MS = 2000;
    /** USB control transfer request type to send data from the host to the device */
    private static final int USB_SEND = 0x40;
    /** USB control transfer request type to retrieve data from the device to the host */
    private static final int USB_RECV = 0xc0;

    /** HELLO request, initiates a handshake that results in the device sending its banner */
    private static final int CMD_HELLO              = 0x01;
    /** Initialize a firmware update */
    private static final int CMD_FWUPDATE_INIT      = 0x10;
    /** Send a single memory page to the device as part of a firmware update process */
    private static final int CMD_FWUPDATE_MEMPAGE   = 0x11;
    /** Retrieve the last memory page back from the device in order to verify it */
    private static final int CMD_FWUPDATE_VERIFY    = 0x12;
    /** Finalize a firmware updating process */
    private static final int CMD_FWUPDATE_FINALIZE  = 0x13;
    /** Gracefully say good bye to the device */
    private static final int CMD_BYE                = 0xf0;
    /** Reset the device */
    private static final int CMD_RESET              = 0xfa;

    /** USB control request value parameter expected by the bootloader for the HELLO command */
    private static final int HELLO_VALUE = 0x4d6f;
    /** USB control request index parameter expected by the bootloader for the HELLO command */
    private static final int HELLO_INDEX = 0x6921;

    /** Permission request identifier to request permission to access USB from the user */
    private static final String ACTION_USB_PERMISSION = "fi.craplab.ledmacher.USB_PERMISSION";

    /** The singleton instance */
    private static UsbHandler instance;

    private final Context context;
    private final UsbManager usbManager;
    private UsbDeviceConnection bootloaderConnection;
    private List<Listener> listeners;
    private PendingIntent permissionIntent;

    /**
     * Listener interface to communicate that a USB device was attached or removed.
     */
    public interface Listener {
        /**
         * USB device of the given {@code deviceType} was attached.
         *
         * This considers every USB device, not only valid Ledmacher Bootloader devices, so make
         * sure to check that {@code deviceType} is {@link DeviceType#LEDMACHER_BOOTLOADER} before
         * any further operations on it.
         *
         * @param deviceType {@link DeviceType} of the freshly attached USB device
         */
        void onDeviceAttached(DeviceType deviceType);

        /**
         * USB device was detached.
         *
         * While this could mean that any device was attached, it's assumed there was only one
         * single device previously attached.
         */
        void onDeviceDetached();
    }

    /**
     * Device type of an attached USB device.
     *
     * These are no universal identifier nor is this anything about USB classes etc, this is just
     * some internal classification for anything related to RUDY.
     */
    public enum DeviceType {
        /** Device is a valid Ledmacher bootloader */
        LEDMACHER_BOOTLOADER,
        /** Device is RUDY, but not Ledmacher */
        RUDY_OTHER,
        /** Device is supposed to be RUDY as VID/PID matches, but product name is unknown */
        RUDY_UNKNOWN,
        /** Device is supposed to be a valid Ledmacher bootloader device, but isn't. */
        LEDMACHER_INVALID,
        /** Valid device but couldn't connect */
        ERROR,
        /** Device is not RUDY (VID/PID don't match) */
        UNKNOWN,
        /** Device hasn't checked its type yet */
        NONE
    }

    /**
     * Get the singleton instance.
     *
     * If the instance doesn't exist yet, it will be created with he given {@code context} now.
     * Use this method to be on the safe side without any need of keeping track whether the
     * instance was created or not. If you are certain an instance already exists, you can also
     * use the {@link #getInstance()} method instead.
     *
     * @param context Context, because who doesn't need context
     * @return The {@link UsbHandler} singleton instance
     */
    public static UsbHandler getInstance(Context context) {
        if (instance == null) {
            instance = new UsbHandler(context);
        }
        return instance;
    }

    /**
     * Get the singleton instance.
     *
     * This expects that an instance actually already exists, as the instance can't be created
     * without some {@link Context}. If you aren't sure if the instance already exists, or are
     * obviously calling it for the very first time, use {@link #getInstance(Context)} instead.
     *
     * @return The {@link UsbHandler} singleton instance
     * @throws IllegalStateException if the singleton instance doesn't exist yet
     */
    public static UsbHandler getInstance() {
        if (instance == null) {
            throw new IllegalStateException("No instance, try getInstance(Context) instead");
        }
        return instance;
    }

    /**
     * Destroy the singleton instance.
     *
     * Everything must come to an end, and so does this singleton once the main activity gets
     * destroyed. Since this class uses some system resources, we should release them in that
     * case. Assuming there is an actual instance, otherwise nothing will happen here.
     */
    public static void destroyInstance() {
        if (instance == null) {
            // nothing to do
            return;
        }

        instance.onDestroy();
        instance = null;
    }

    /**
     * Create the actual {@code UsbHandler} object with the given {@code context}.
     *
     * Sets up everything for receiving attach and detach intents from the system, and setting up
     * the permissions to do so in the first place.
     *
     * @param context Context, man
     */
    private UsbHandler(Context context) {
        this.context = context;
        usbManager = context.getSystemService(UsbManager.class);

        if (usbManager == null) {
            // TODO show message that sorry, cannot do anything, no USB manager system service
            throw new UnsupportedOperationException();
        }

        listeners = new ArrayList<>();

        permissionIntent =
                PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        context.registerReceiver(usbPermissionBroadcastReceiver, filter);

        IntentFilter usbIntentFilter = new IntentFilter();
        usbIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(usbDeviceBroadcastReceiver, usbIntentFilter);
    }

    /**
     * Destroy the object.
     *
     * Called when the singleton instance is destroyed, and unregisters all the event receivers.
     */
    private void onDestroy() {
        closeDeviceConnection();
        context.unregisterReceiver(usbDeviceBroadcastReceiver);
        context.unregisterReceiver(usbPermissionBroadcastReceiver);
    }

    /**
     * Add a listener that will get notified about devices being attached / detached.
     *
     * @param listener Listener interface implementer
     */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     *
     * @param listener Listener interface implementer
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Check if there are any devices already attached yet.
     *
     * If a device gets attached while the app is running, we'll get the event about that from the
     * system, but if there is already a device attached when we start the app, we won't get any
     * notification about it. So we have to check manually. This should be therefore called at a
     * very early moment of the app's lifecycle.
     *
     * As access to USB requires properly set permissions, we also can't just simply check and
     * return the information back to the caller, but must be prepared to take care of the
     * permission handling first. This method triggers all that and if all is sorted out, and
     * there is indeed a device connected, the {@link Listener#onDeviceAttached(DeviceType)}
     * callback is executed.
     */
    public void checkForDevice() {
        List<UsbDevice> list = new ArrayList<>(usbManager.getDeviceList().values());

        if (list.isEmpty()) {
            // ain't got no devices, nothing to do
            return;
        }

        doStuffBasedOnPermission(list.get(0));
    }

    /**
     * Do something with the given {@code device}, depending on permission setup.
     *
     * If permission to access USB was granted, this will go on and handle the given {@code device}
     * as freshly attached device by checking what kind of device it is, and notifying about it
     * via the {@link Listener#onDeviceAttached(DeviceType)} callback.
     *
     * If permissions are NOT granted, setting up those permissions will be handled instead here.
     * However the choice of granting permissions, this method here is called again afterwards,
     * and on and on we spin in circles.
     *
     * @param device New USB device to look into
     */
    private void doStuffBasedOnPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            handleNewDevice(device);
        } else {
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    /**
     * Handle USB permission request.
     *
     * All this here doesn't really work if the user doesn't grant permissions to use USB.
     */
    private final BroadcastReceiver usbPermissionBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            handleNewDevice(device);
                        }
                    } else {
                        Log.w(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };

    /**
     * Receive system events about new USB devices coming, and old ones leaving
     */
    private final BroadcastReceiver usbDeviceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                bootloaderConnection = null;

                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    doStuffBasedOnPermission(device);
                }

            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                bootloaderConnection = null;

                for (Listener listener : listeners) {
                    listener.onDeviceDetached();
                }
            }
        }
    };

    /**
     * Handle a newly attached device.
     *
     * Determines the device's {@link DeviceType} and informs the listeners about it.
     *
     * @param device New attached USB device
     */
    private void handleNewDevice(@NonNull UsbDevice device) {
        DeviceType deviceType = getDeviceType(device);

        if (deviceType != DeviceType.NONE) {
            for (Listener listener : listeners) {
                listener.onDeviceAttached(deviceType);
            }
        }
    }

    /**
     * Determine the {@link DeviceType} of the given {@code device}.
     *
     * This goes the whole chain, starting from the USB VID/PID values, the product name and
     * serial number strings, and if all that matches to what we're expecting, connect to the
     * device itself and read what banner it sends to identify itself.
     *
     * In normal circumstances, the VID/PID value could be enough, but since this is based on
     * RUDY - the Random USB Device - the device could be just anything. So better quadruple check.
     *
     * @param device USB device to check its type for
     * @return Type of the given device
     */
    private DeviceType getDeviceType(UsbDevice device) {
        if (isCraplabRudy(device)) {
            if (!BOOTLOADER_PRODUCT_NAME.equals(device.getProductName())) {
                return DeviceType.RUDY_UNKNOWN;
            }

            if (!BOOTLOADER_SERIAL_NUMBER.equals(device.getSerialNumber())) {
                return DeviceType.RUDY_OTHER;
            }

            return checkBootloader(device);
        }

        return DeviceType.UNKNOWN;
    }

    /**
     * Check whether the given {@code device} is RUDY based on its VID/PID pair values.
     *
     * @param device USB device to check
     * @return {@code true} if the device identifies as RUDY, {@code false} otherwise
     */
    private boolean isCraplabRudy(UsbDevice device) {
        return (device.getVendorId() == CRAPLAB_VID &&
                device.getProductId() == RUDY_PID);
    }

    /**
     * Check if the given {@code device} is a Ledmacher Bootloader.
     *
     * Sends a HELLO command and checks if the device returns a banner string telling that it
     * identifies as a Ledmacher Bootloader.
     *
     * Note that this expects a valid established connection to the given {@code device},
     * otherwise {@link DeviceType#ERROR} is returned. As this method is called at the end of the
     * whole check chain in {@link #getDeviceType(UsbDevice)}, the valid connection implies that
     * all other checks have succeeded to far, and from USB identification point of view, the
     * given {@code device} at least claims to be a Ledmacher device.
     *
     * @param device USB device to check
     * @return {@code DeviceType#LEDMACHER_BOOTLOADER} if device is a Ledmacher Bootloader device,
     *         {@code DeviceType#LEDMACHER_INVALID} if it isn't,
     *         {@code DeviceType#ERROR} if we're not connected to any device at all
     * @see #sendHello()
     */
    private DeviceType checkBootloader(UsbDevice device) {
        if (!openDeviceConnection(device)) {
            return DeviceType.ERROR;
        }

        String bootloaderVersionString = sendHello();
        Log.i(TAG, "Bootloader banner received: " + bootloaderVersionString);
        sendBye();

        if (bootloaderVersionString.startsWith(BOOTLOADER_BANNER_PREFIX)) {
            // all good, continue
            Log.d(TAG, "Found valid bootloader");
            return DeviceType.LEDMACHER_BOOTLOADER;
        }

        closeDeviceConnection();
        return DeviceType.LEDMACHER_INVALID;
    }

    /**
     * Open a connection to the given {@code device}.
     *
     * @param device USB device to connect to
     * @return {@code true} if connection was successfully established, {@code false} otherwise
     */
    private boolean openDeviceConnection(UsbDevice device) {
        bootloaderConnection = null;

        if (device.getInterfaceCount() == 0) {
            Log.e(TAG, "Don't have interfaces");
            return false;
        }

        UsbInterface usbInterface = device.getInterface(0);
        if (usbInterface.getEndpointCount() == 0) {
            Log.e(TAG, "Don't have endpoints on interface " + usbInterface);
            return false;
        }

        UsbEndpoint endpoint = usbInterface.getEndpoint(0);
        Log.i(TAG, "Opened endpoint " + endpoint.getEndpointNumber() + " of interface " +
                usbInterface.getId());

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Cannot open device connection");
            return false;
        }
        connection.claimInterface(usbInterface, true);

        bootloaderConnection = connection;
        return true;
    }

    /**
     * Close an ongoing connection to the Ledmacher Bootloader device.
     *
     * Being an honorable app, we send a BYE command before disconnecting. And if there's no
     * active connection in the first place, it won't do any of that.
     */
    private void closeDeviceConnection() {
        if (hasValidConnection()) {
            sendBye();
            bootloaderConnection.close();
            bootloaderConnection = null;
        }
    }

    /**
     * Check if there's a connection to a valid Ledmacher Bootloader device established.
     *
     * @return {@code true} if we're connected to a valid device, {@code false} otherwise
     */
    private boolean hasValidConnection() {
        return bootloaderConnection != null;
    }

    /**
     * Enforce that we're connected to a valid Ledmacher Bootloader device by throwing an
     * {@link IllegalStateException} if we're not.
     *
     * This is meant as a one-line shortcut for any interaction with the device.
     *
     * @throws IllegalStateException if there's no connection to a valid device
     */
    private void enforceValidConnection() {
        if (!hasValidConnection()) {
            throw new IllegalStateException();
        }
    }

    /**
     * Perform HELLO command request.
     *
     * As part of the HELLO request, the bootloader returns its banner string, telling what it
     * actually is. For that to happen, a magic number value/index parameter pair is expected.
     *
     * Also this is gonna fail miserably if performed on a device that doesn't understand any
     * of this, so it makes sure that there is a valid connection to the right USB device
     * established in the first place.
     *
     * @return Bootloader banner string containing its name and version
     * @throws IllegalStateException if there's no connection to a valid device
     */
    @NonNull
    private String sendHello() {
        enforceValidConnection();

        byte[] buffer = new byte[PAGE_SIZE];
        int ret = bootloaderConnection.controlTransfer(USB_RECV, CMD_HELLO, HELLO_VALUE, HELLO_INDEX, buffer, buffer.length, USB_TIMEOUT_MS);
        // Bootloader version string contains trailing \0, remove it here and return received buffer data as string
        return new String(buffer, 0, ret - 1);
    }

    /**
     * Performs firmware update initialization command request.
     *
     * The initialization part contains the number of memory pages to expect.
     *
     * @param numberOfPages Number of memory pages the upcoming firmware is going to have
     * @throws IllegalStateException if there's no connection to a valid device
     * @see FirmwareHandler#getNumberOfPages()
     */
    private void sendInit(int numberOfPages) {
        enforceValidConnection();
        bootloaderConnection.controlTransfer(USB_SEND, CMD_FWUPDATE_INIT, numberOfPages, 0, null, 0, USB_TIMEOUT_MS);
    }

    /**
     * Performs firmware mempage transfer command request.
     *
     * Sends a {@value PAGE_SIZE} byte chunk of firmware data to the device for it to flash as its
     * new application firmware.
     *
     * @param data Raw bytes of firmware
     * @param len Length of data sent to the device
     * @throws IllegalStateException if there's no connection to a valid device
     */
    private void sendMemPage(byte[] data, int len) {
        enforceValidConnection();
        bootloaderConnection.controlTransfer(USB_SEND, CMD_FWUPDATE_MEMPAGE, 0, 0, data, len, USB_TIMEOUT_MS);
    }

    /**
     * Performs firmware mempage validation command request.
     *
     * The device will send back the last received chunk of firmware so we can compare if sending
     * the chunk actually succeeded.
     *
     * @param buffer Receive buffer for the firmware chunk sent back by the device
     * @param bufferLen Length of the received data
     * @throws IllegalStateException if there's no connection to a valid device
     */
    private void sendVerify(byte[] buffer, int bufferLen) {
        enforceValidConnection();
        bootloaderConnection.controlTransfer(USB_RECV, CMD_FWUPDATE_VERIFY, 0, 0, buffer, bufferLen, USB_TIMEOUT_MS);
    }

    /**
     * Performs firmware update finalization command request.
     *
     * Finishes up the firmware update on the device side.
     *
     * @throws IllegalStateException if there's no connection to a valid device
     */
    private void sendFinalize() {
        enforceValidConnection();
        bootloaderConnection.controlTransfer(USB_SEND, CMD_FWUPDATE_FINALIZE, 0, 0, null, 0, USB_TIMEOUT_MS);
    }

    /**
     * Performs BYE command request.
     *
     * Tells the device we're not interested in anything else anymore at this point, so it can go
     * back to idle and wait for more later on.
     *
     * @throws IllegalStateException if there's no connection to a valid device
     */
    private void sendBye() {
        enforceValidConnection();
        bootloaderConnection.controlTransfer(USB_SEND, CMD_BYE, 0, 0, null, 0, USB_TIMEOUT_MS);
    }

    /**
     * Performs reset command request.
     *
     * Resets the device, and if no bootloader enable button is pressed, starts the application
     * code straight away.
     *
     * @throws IllegalStateException if there's no connection to a valid device
     */
    private void sendReset() {
        enforceValidConnection();
        bootloaderConnection.controlTransfer(USB_SEND, CMD_RESET, 0, 0, null, 0, USB_TIMEOUT_MS);
    }


    /**
     * Initialize an actual firmware flash process.
     *
     * This is called from within the {@link FirmwareFlashTask}.
     *
     * @param numberOfPagesToCome Number of memory pages the firmware is going to have
     */
    void initiateFirmwareFlash(int numberOfPagesToCome) {
        Log.d(TAG, "Initiating firmware transfer of " + numberOfPagesToCome + " pages");
        sendHello();
        sendInit(numberOfPagesToCome);
    }

    /**
     * Flash a single memory page.
     *
     * This is called from within the {@link FirmwareFlashTask}.
     *
     * The memory page is both written and read back to verify that the content matches and
     * everything went well during the transfer. If the content doesn't match, the memory page
     * is sent again for all eternity until it does match. The amount of actual attempts is
     * in the end returned then.
     *
     * While the content mismatch should be more of an exception in an ideal world, it happens
     * actually quite regularly, and like multiple times within one firmware update process.
     * Whether that's a drawback of the V-USB library used on the device, or a flaw in the
     * communication implementation otherwise remains to be seen.
     *
     * @param pageData Raw bytes of memory page data
     * @param pageSize Size of the data in this page
     * @return Number of retries it took to have the correct data flashed.
     */
    int flashFirmwarePage(byte[] pageData, int pageSize) {
        int retryCount = 0;
        boolean verified = false;

        byte[] verifyData = new byte[PAGE_SIZE];
        while (!verified) {
            sendMemPage(pageData, pageSize);
            sendVerify(verifyData, verifyData.length);

            verified = true;
            for (int i = 0; i < pageSize - HEADER_SIZE; i++) {
                if (pageData[HEADER_SIZE + i] != verifyData[i]) {
                    verified = false;
                    break;
                }
            }
            retryCount++;
        }

        return retryCount;
    }

    /**
     * Finalizes a firmware update process.
     *
     * Based on the reset setting in the {@link SharedPreferences} a reset might be triggered
     * right away as well, booting straight into the freshly flashed application firmware.
     *
     * This is called from within the {@link FirmwareFlashTask}.
     */
    void finalizeFirmwareFlash() {
        sendFinalize();
        sendBye();

        SharedPreferences sharedPrefs = context.getSharedPreferences(
                context.getString(R.string.prefs_name), Context.MODE_PRIVATE);
        boolean resetDevice = sharedPrefs.getBoolean(
                context.getString(R.string.prefs_key_reset_after_flash),
                context.getResources().getBoolean(R.bool.params_reset_device_default));
        Log.d(TAG, "BYE sent, reset device: " + resetDevice);

        if (resetDevice) {
            sendReset();
        }
    }
}

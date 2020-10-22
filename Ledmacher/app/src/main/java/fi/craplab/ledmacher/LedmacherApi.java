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

import fi.craplab.ledmacher.model.BuildResponse;
import fi.craplab.ledmacher.model.Config;
import fi.craplab.ledmacher.model.FirmwareData;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Retrofit REST API definition for communicating with the Ledmacher backend.
 */
public interface LedmacherApi {
    /**
     * Request a firmware build from the backend.
     *
     * POST request sending the current {@link Config} as JSON to the backend, which then triggers
     * the firmware build, replying with its build hash in the {@link BuildResponse} returned.
     *
     * @param config Config object consisting of current color configuration and general firmware
     *               parameters set up in the {@link fi.craplab.ledmacher.ui.ParameterConfigDialog}
     * @return BuildResponse object containing the firmware hash used for further requests
     */
    @POST("/firmware")
    Call<BuildResponse> buildNewFirmware(@Body Config config);

    /**
     * Request to retrieve all information of a firmware build with the given {@code hash}.
     *
     * The {@code hash} parameter itself must match a previous build requested via the
     * {@link #buildNewFirmware(Config)} call - or any other good guess of a valid hash.
     *
     * @param hash Firmware build hash
     * @return {@link FirmwareData} containing all the information about the requested firmware
     */
    @GET("/firmware/{hash}")
    Call<FirmwareData> getFirmwareInformation(@Path("hash") String hash);

    /**
     * Request to retrieve the binary data of firmware build with the given {@code hash}.
     *
     * The {@code hash} parameter itself must match a previous build requested via the
     * {@link #buildNewFirmware(Config)} call. The retrieved data is what gets sent to the device
     * via USB and actually ends up flashed as application firmware on it.
     *
     * @param hash Firmware build hash
     * @return Raw bytes of firmware packed inside a {@link ResponseBody}
     */
    @GET("/firmware/{hash}/bin")
    Call<ResponseBody> getFirmwareBinary(@Path("hash") String hash);
}

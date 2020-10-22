#!/usr/bin/env python3
#
# Ledmacher Backend - Main Python Script
# Does the whole backend thing ..so, yeah, don't expect too much from it.
#
# Copyright (C) 2020 Sven Gregori <sven@craplab.fi>
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

#
#  +----------------  B I G   F A T   W A R N I N G   N O T E  ---------------+
#  |                                                                          |
#  |  This is a proof of concept that should never be run as-is in the wild!  |
#  |  Like, seriously.                                                        |
#  |                                                                          |
#  |  There's practically no input sanitation and barely any error handling   |
#  |  done in here, and everything is just happily passed on to a shell that  |
#  |  will execute a script that has a similar level of sanitation and error  |
#  |  handling implemented.                                                   |
#  |                                                                          |
#  |  With normal usage, things should be fine without causing any serious    |
#  |  harm in case of passing around bad data (famous last words), but still, |
#  |  it's probably a bad idea to run this publicly for anyone to access.     |
#  |                                                                          |
#  +--------------------------------------------------------------------------+
#

#
# To test, use curl with the sample.json file to request a firmware build:
#   curl -X POST -H "content-type: application/json" localhost:5544/firmware -d "$(cat sample.json)"
#
#   -> returns a JSON response containing the hash, e.g.:
#   {"hash": "2bae9b9d4e46f8bfd7cb93ff27a79e841d124c3d"}
#
#
# Request additional information from the build:
#   curl -X GET -H "content-type: application/json" localhost:5544/firmware/2bae9b9d4e46f8bfd7cb93ff27a79e841d124c3d
#
#   -> returns JSON response containing size, binary file checksum, date, and the original config content
#
# Request the firmware file itself:
#   curl -X GET -H "content-type: application/json" localhost:5544/firmware/2bae9b9d4e46f8bfd7cb93ff27a79e841d124c3d/bin -OJ
#
#   -> retrieves and writes ledmacher.bin (note the -OJ parameter for that to actually happen)
#

import hashlib
import os
import json
import bottle
import subprocess
import time


@bottle.route('/')
def index():
    return "It works!"


@bottle.post('/firmware')
def build_firmware():
    """
    Request a firmware build.

    The configuration data is expected as JSON data within the POST request and used to create
    header file information for the ./buildme.sh script.

    If the build succeeds, the hash returned from the ./buildme.sh script is sent as JSON data
    back to the caller, to be used for any future requests regarding the firmware itself.

    If anything failed along the build, a 500 reponse is sent instead.
    """

    # Print and collect data about the request
    print(bottle.request)
    print(bottle.request.json)
    json_data = bottle.request.json
    client = bottle.request.environ.get('REMOTE_ADDR')

    # Collect configuration to be sent to the ./buildme.sh script
    out_data = """
#define NUM_LEDS {num_leds:d}

#define WAIT_COLOR_MS {wait_color:d}
#define WAIT_GRADIENT_MS {wait_gradient:d}
#define GRADIENT_STEPS {gradient_steps:d}

""".format(**json_data)

    # Add color definitions to the config data
    out_data += "struct cRGB colors[] = {\n"
    for c in json_data['colors']:
        out_data += "    {{ .r = {r:3}, .g = {g:3}, .b = {b:3} }},\n".format(**c)
    out_data += "};\n"

    # Call the script, opening up pipes for input and output to pass config data and get the hash back
    process = subprocess.Popen(['./buildme.sh', client], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    out, err = process.communicate(out_data.encode('utf-8'))
    returncode = process.returncode;
    firmware_hash = out.decode('utf-8')

    print("firmware hash: {}".format(firmware_hash))
    print("return code: {}".format(returncode))

    # If for whatever reason there's no hash written from the ./buildme.sh script, abort
    if firmware_hash is None or firmware_hash == "":
        # TODO dump the retrieved JSON data and build output to a log file, just in case
        bottle.abort(500, "Yeah, this didn't work")
    
    # Write the original content as config.json file to the build directory
    build_dir = './build/{}'.format(firmware_hash)
    json_file = '{}/config.json'.format(build_dir)
    if os.path.isdir(build_dir):
        with open(json_file, 'w') as outfile:
            json.dump(json_data, outfile)

    # If all went well, return the hash, otherwise, don't
    if returncode == 0:
        time.sleep(1)
        return dict(hash=firmware_hash)
    else:
        bottle.abort(500, "Build failed")


@bottle.get('/firmware/<firmware_hash>')
def get_firmware_info(firmware_hash):
    """
    Retrieve information of a previous firmware build.

    If the given firmware hash exists, all available information is collected and returned as JSON.
    Included information is the create time, binary file size, binary file SHA1 checksum, as weel as
    the original configuration data content.

    If the given firmware hash doesn't exist, 404 response is sent.
    """

    build_dir = './build/{}'.format(firmware_hash)
    config_file = '{}/config.json'.format(build_dir)
    firmware_file = '{}/ledmacher.bin'.format(build_dir)

    if os.path.isfile(config_file):
        with open(config_file) as json_file:
            config_data = json.load(json_file)

        created = int(os.path.getctime(config_file))
        firmware_size = os.path.getsize(firmware_file)
        firmware_checksum = hashlib.sha1(open(firmware_file, 'rb').read()).hexdigest()

        return dict(
                build_hash=firmware_hash,
                date=created,
                size=firmware_size,
                checksum=firmware_checksum,
                config=config_data)

    return bottle.abort(404, "Firmware not found")


@bottle.get('/firmware/<firmware_hash>/bin')
def download_firmware(firmware_hash):
    """
    Rertieve the binary file of a previous firmware build.

    If there's a build directory with the given firmware hash, and a ledmacher.bin file exists in it,
    the file is sent as binary octet-stream content.

    If there's no such file, 404 response is sent instead.
    """

    filename = 'ledmacher.bin'
    filepath = './build/{}/{}'.format(firmware_hash, filename)

    if os.path.isfile(filepath):
        # download='' option defines via Content-disposition header what filename should be.
        #  Chromium follows that
        #  curl needs -OJ parameter (-O to define 'use remote name as output file' and -J to say 'follow that header')
        #  wget needs --content-disposition header
        time.sleep(1)
        return bottle.static_file(filepath, root='./', mimetype='application/octet-stream', download=filename)

    bottle.abort(404, "Nope")


if __name__ == '__main__':
    bottle.run(host='0.0.0.0', port=5544)
else:
    application = bottle.default_app()
    print("TODO: do something with this")


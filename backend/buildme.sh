#!/bin/bash
#
# Ledmacher Backend - Build Script
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
# Builds the Ledmacher device firmware by creating a session-specific build
# directory, writing a created.h header file from a given input stream, and
# running make on it. The session hash itself is then written out, and the
# script exits however successful or unsuccessful the built itself was.
#
# Usage
#   cat sample.json.out | ./buildme.sh [<client string>]
#
# Normally this is called from the backend.py Python script as part of the
# whole build-from-app chain which converts a JSON file received from the
# app into raw .h content that's expected here, but the sample.json.out
# file can be used for testing.
#
# An optional client string can be given (e.g. IP address) that is used
# when creating the session hash. A later version could actually just
# use the hash of the config data itself, allowing to re-use previous
# builds for identical configurations (something to consider at least).
#
# NOTE: As the Python script is reading back the output and expecting
# the session hash, all communication to the user / shell itself *MUST*
# be written to stderr instead of stdout!
#

# Check that there's data coming straight from stdin, or abort if not
if [ ! -p /dev/stdin ] ; then
    >&2 echo "Usage: <generate some output> | $0 [<client string>]"
    exit 1
fi

BASE_DIR="./build/base"

# Make sure the base build directory (i.e. the base for all session-specific
# builds containing the device firmware sources) exists, and if not, create
# it and create symbolic links to the firmware files inside of it.
#
# This way, there's no need to drag the build directory itself around in
# version control, and cleaning up old builds can be as easy as just wiping
# the entire build directory - it'll be back the next time it's needed.
if [ ! -d $BASE_DIR ] ; then
    SRC_DIR="$(readlink -f ../device)"
    BUILD_SOURCE_FILES="light_ws2812.c light_ws2812.h main.c Makefile"

    >&2 echo "Build base directory doesn't exist, setting it up"
    mkdir -p $BASE_DIR
    for file in $BUILD_SOURCE_FILES ; do
        >&2 echo "linking $file"
        if [ ! -e $SRC_DIR/$file ] ; then
            >&2 echo "ERROR: $SRC_DIR/$file doesn't exist"
            exit 1
        fi
        ln -s $SRC_DIR/$file $BASE_DIR/$file
    done
fi


# Create session-specific build hash
# The hash is just the SHA1 checksum of the given client ID and the current
# time's string representation concatenated to "<client> <date string>"
client="$1"
now=$(date)
build_hash=$(echo $client $now | sha1sum | cut -d\  -f 1)

# Create session-specific build directory simply named like the hash
build_dir=build/$build_hash

# Copy base directory to session-specific build directory
>&2 echo "Creating $build_hash"
cp -ar build/base $build_dir

# Create header file and dump given input stream into it
# Obviously if there's just garbage in the input stream, the actual build
# will fail afterwards.
header=$build_dir/created.h

cat > $header << EOF
/*
 * $build_hash
 * $now
 * $client
 */
#ifndef _CREATED_H_
#define _CREATED_H_

EOF

while IFS= read line ; do
    echo "$line" >> $header
done

cat >> $header << EOF

#endif /* _CREATED_H_ */
EOF

# Run make to create the .bin file and dump the output to a log file
make -C $build_dir bin >$build_dir/build.log 2>&1
declare -i build_retval=$?

# If build fails, print the log to stderr
if [ $build_retval -ne 0 ] ; then
    >&2 echo "BUILD FAILED!"
    >&2 cat $build_dir/build.log
fi

# Print hash either way, the Python script will check the return value
# to determine success / failure, but the hash is needed either way.
echo -n $build_hash

# Exit with whatever value the build process itself exited
exit $build_retval


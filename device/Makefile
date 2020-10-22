#
# Ledmacher Device Application - Makefile
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

MCU = atmega328p
F_CPU = 12000000
AVRDUDE_PROGRAMMER = -c usbasp

PROGRAM=ledmacher
OBJS = main.o light_ws2812.o

CC = avr-gcc
OBJCOPY = avr-objcopy
SIZE = avr-size
AVRDUDE = avrdude

CFLAGS += -g -Os -std=gnu99 -I.\
-funsigned-char -funsigned-bitfields -fpack-struct -fshort-enums \
-Wall -Wextra -Wstrict-prototypes \
-DF_CPU=$(F_CPU) -mmcu=$(MCU)

LDFLAGS = -Wl,-Map=$(PROGRAM).map,--cref

AVRDUDE_FLAGS = -p $(MCU) $(AVRDUDE_PROGRAMMER)


.PRECIOUS : %.elf %.o

all: bin

bin: $(PROGRAM).bin
hex: $(PROGRAM).hex

$(PROGRAM).elf: $(OBJS)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

$(PROGRAM).hex: $(PROGRAM).elf
	$(OBJCOPY) -O ihex -R .eeprom $< $@
	@$(SIZE) $<

$(PROGRAM).bin: $(PROGRAM).elf
	$(OBJCOPY) -O binary -R .eeprom $< $@
	@$(SIZE) $<

flash: $(PROGRAM).hex
	@echo ""
	@echo "  +---------------------------------------------------------+"
	@echo "  |                         WARNING                         |"
	@echo "  |                                                         |"
	@echo "  | You are about to remove the bootloader from the device! |"
	@echo "  |                                                         |"
	@echo "  | If you choose to proceed, be aware that the device will |"
	@echo "  | only run the application code (i.e. what's built here)  |"
	@echo "  | from now on, and updating the device using the external |"
	@echo "  | tools won't be possible anymore (unless the bootloader  |"
	@echo "  | is flashed back to the device, so no worries, you won't |"
	@echo "  | brick the device either here).                          |"
	@echo "  |                                                         |"
	@echo "  | To flash bootloader with the application code, please   |"
	@echo "  | change to the ../bootloader/ directory and run the      |"
	@echo "  |         make flash-all                                  |"
	@echo "  | target from within that directory instead.              |"
	@echo "  +---------------------------------------------------------+"
	@echo ""
	@read -p "Are you sure you want to proceed? [y/N] " choice; \
	[ "$${choice,,}" == "y" ] || (\
		echo "Aborting"; \
		exit 1; \
	)
	$(AVRDUDE) $(AVRDUDE_FLAGS) -U flash:w:$(PROGRAM).hex

clean:
	rm -f $(OBJS)
	rm -f $(OBJS:.o=.lst)

distclean: clean
	rm -f $(PROGRAM).elf $(PROGRAM).hex $(PROGRAM).map $(PROGRAM).bin

.PHONY : all bin hex flash clean distclean


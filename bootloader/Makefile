#
# Ledmacher Bootloader - Makefile
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

PROGRAM=ledmacher-bootloader
APPLICATION=$(wildcard ../device/ledmacher.hex)

OBJS = main.o uart.o 
OBJS += light_ws2812.o
OBJS += usbdrv/usbdrv.o usbdrv/usbdrvasm.o


CC = avr-gcc
OBJCOPY = avr-objcopy
SIZE = avr-size
AVRDUDE = avrdude

CFLAGS += -g -Os -std=gnu99 -I.\
-funsigned-char -funsigned-bitfields -fpack-struct -fshort-enums \
-Wall -Wextra -Wstrict-prototypes \
-DF_CPU=$(F_CPU) -mmcu=$(MCU)

ASFLAGS = -Wa,-adhlms=$(<:.c=.lst),-gstabs 
ASFLAGS_ASM = -Wa,-gstabs 
LDFLAGS = -Wl,-Map=$(PROGRAM).map,--cref

BOOTLOAD_ADDR = 0x7000
LDFLAGS += -Wl,--section-start=.text=$(BOOTLOAD_ADDR)


AVRDUDE_FLAGS = -p $(MCU) $(AVRDUDE_PROGRAMMER)


.PRECIOUS : %.elf %.o

all: $(PROGRAM).hex

debug: CFLAGS+= -DDEBUG
debug: $(PROGRAM).hex
	

$(PROGRAM).hex: $(PROGRAM).elf
	$(OBJCOPY) -O ihex -R .eeprom $< $@
	@$(SIZE) $^

$(PROGRAM).elf: $(OBJS)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

.c.o:
	$(CC) -c $(CFLAGS) $(ASFLAGS) $< -o $@

.S.o:
	$(CC) -c $(CFLAGS) -x assembler-with-cpp $(ASFLAGS_ASM) $< -o $@

fuses:
	# External full swing crystal oscillator, 16K CK / 14 CK + 65ms
	# 2048 words (4196 bytes) bootloader space and BOOT RESET VECTOR ENABLED
	$(AVRDUDE) $(AVRDUDE_FLAGS) -U lfuse:w:0xf7:m -U hfuse:w:0xd8:m -U efuse:w:0xff:m

flash:
	@echo ""
	@echo "  +-------------------------------------------------------+"
	@echo "  |                         NOTE:                         |"
	@echo "  | Flashing only the bootloader without any application. |"
	@echo "  |                                                       |"
	@echo "  | If you want to flash both at the same time, use the   |"
	@echo "  |         make flash-all                                |"
	@echo "  | target instead please.                                |"
	@echo "  +-------------------------------------------------------+"
	@echo ""
	$(AVRDUDE) $(AVRDUDE_FLAGS) -U flash:w:$(PROGRAM).hex

flash-all: $(PROGRAM).hex
ifeq (,$(APPLICATION))
	@echo "Application not built yet, building it now..."
	@echo ""
	make -C ../device hex
	@echo ""
	@echo "Application built."
	@echo ""
	@echo "Please run \"make flash-all\" again"
	@echo ""
else
	$(AVRDUDE) $(AVRDUDE_FLAGS) -U flash:w:$(APPLICATION) -U flash:w:$(PROGRAM).hex
endif

clean:
	rm -f $(OBJS)
	rm -f $(OBJS:.o=.lst)

distclean: clean
	rm -f $(PROGRAM).elf $(PROGRAM).hex $(PROGRAM).map

.PHONY : all debug flash flash-all clean distclean


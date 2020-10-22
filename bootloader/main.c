/*
 * Ledmacher Bootloader - Main
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
#include <string.h>
#include <avr/boot.h>
#include <avr/interrupt.h>
#include <avr/io.h>
#include <avr/wdt.h>
#include <util/delay.h>
#include "uart.h"
#include "usbconfig.h"
#include "usbdrv/usbdrv.h"
#include "light_ws2812.h"

/*
 * The Ledmacher Bootloader
 * A simple USB device to receive and flash new application firmware.
 *
 * By default, the bootloader is ignored as the Ledmacher device is
 * supposed to make LEDs shine. To activate the bootloader, a defined
 * input pin needs to read zero (by pressing a push button connected
 * to GND for example), after which USB is initialized and the device
 * is ready to receive new firmware to flash as application code.
 *
 * Note, the pin state is read as one of the very first things the
 * bootloader is going to do, so best way to activate the bootloader
 * is to keep the button pressed while either plugging in the USB
 * cable, applying power otherwise to the device, or triggering a
 * regular reset.
 *
 * An activated bootloader is indicated by a single dimly lit LED.
 *
 *
 * Note, the bootloader can be compiled with extra debug information.
 *
 * Without debug information, there's minimal output written via UART,
 * mainly just a banner, the bootloader enable pin state, and whichever
 * command was received. If you're not planning on developing on the
 * bootloader itself, this should be fine (assuming use UART at all).
 *
 * To enable debug information, use the nodebug Makefile target which
 * passes -DDEBUG to the compiler (both uart.c and main.c need DEBUG
 * defined during compilation, so can't just simply #define it here).
 *
 * With debug information enabled, excessive additional information
 * is written to UART, including the entire firmware byte by byte as
 * it is received. While this is interesting to see, it significantly
 * slows down the flashing processing, so it's probably best to not
 * use the debug mode on a normal basis.
 *
 * Also, enabling debug information adds roughly an extra 1kB to the
 * rather sparse memory of the bootloader section.
 */

/*
 * Specifiy which GPIO pin will be used to enable
 * the bootloader after a device reset.
 *
 * Here: PB0 (ATmega328 pin 14 / Arduino digital pin 8)
 */
/** Bootloader enable pin PORT registser */
#define BOOTLOADER_ENABLE_PORT PORTB
/** Bootloader enable pin DDR registser */
#define BOOTLOADER_ENABLE_DDR DDRB
/** Bootloader enable pin PIN registser */
#define BOOTLOADER_ENABLE_PORT_IN PINB
/** Bootloader enable pin number */
#define BOOTLOADER_ENABLE_PIN  0

/** Bootloader version string */
#define VERSION "1.0"
/** Bootloader banner, sent as response to a valid CMD_HELLO request */
uint8_t banner[] = "Ledmacher Bootloader " VERSION;

void wdt_init(void) __attribute__((naked)) __attribute__((section(".init3")));
void program(void);

/** Expected length of data to receive during CMD_FWUPDATE_MEMPAGE request */
static uint16_t recv_len;
/** Actual length of data received so far in a CMD_FWUPDATE_MEMPAGE request */
static uint16_t recv_cnt;
/** Flag to check if all the expected data has been received */
static uint8_t recv_all;

/** Firmware data chunk */
typedef struct {
    /** Memory page number this chunk should be written to */
    uint8_t page;
    /** Size of the data within this chunk */
    uint8_t size;
    /** The actual data */
    uint8_t data[SPM_PAGESIZE];
} recv_chunk_t;

/** Number of total memory pages to write during a firmware update process */
uint8_t number_of_pages;

/** Firmware chunk data received from the host */
static recv_chunk_t recv_data;

/** Total number of bytes to send in a CMD_FWUPDATE_VERIFY request */
static uint8_t repl_len;
/** Number of bytes sent so far in a CMD_FWUPDATE_VERIFY request */
static uint8_t repl_cnt;


/** USB request to establish a connection */
#define CMD_HELLO               0x01
/** USB request to initialize firmware update */
#define CMD_FWUPDATE_INIT       0x10
/** USB request to send a new memory page during firmware update */
#define CMD_FWUPDATE_MEMPAGE    0x11
/** USB request to verify the last sent memory page */
#define CMD_FWUPDATE_VERIFY     0x12
/** USB request finalize the firmware update */
#define CMD_FWUPDATE_FINALIZE   0x13
/** USB request to end an ongoing connection */
#define CMD_BYE                 0xf0
/** USB request to reset the device */
#define CMD_RESET               0xfa

/** Device is in idle state, waiting for CMD_HELLO */
#define ST_IDLE     0
/** Device has received CMD_HELLO, waiting to initialize firmware update */
#define ST_HELLO    1
/** Firmware update initialized, waiting to receive memory page data */
#define ST_FWUPDATE 2
/** Device is going to reset */
#define ST_RESET    3
/** The device's internal state */
uint8_t state = ST_IDLE;

/** Magic number epxected as value parameter in a CMD_HELLO request */
#define HELLO_VALUE 0x4d6f
/** Magic number epxected as index parameter in a CMD_HELLO request */
#define HELLO_INDEX 0x6921

/** Maximum number of LEDs */
#define NUM_LEDS 8
/** The LEDs */
struct cRGB leds[NUM_LEDS];


/**
 * V-USB setup callback function.
 *
 * Handle all the control transfer commends, i.e. handle the main parts of
 * all the USB communication between the host and the bootloader.
 */
uchar
usbFunctionSetup(uchar data[8])
{
    usbRequest_t *rq = (void *) data;

    switch (rq->bRequest) {
        case CMD_HELLO:
            /*
             * HELLO expects the device to be in idle state and the defined
             * magic numbers as index and value parameters (which, when
             * combined, results in the Finnish greeting "Moi!" in ASCII)
             */
            if (state == ST_IDLE &&
                    rq->wValue.word == HELLO_VALUE &&
                    rq->wIndex.word == HELLO_INDEX)
            {
                uart_print("HELLO\r\n");
                state = ST_HELLO;
                /*
                 * Send banner as response back to the host so it can
                 * verify this is a device it actually expects.
                 */
                usbMsgPtr = banner;
                return sizeof(banner);
            }
            break;

        case CMD_FWUPDATE_INIT:
            /*
             * Initialize firmware update process.
             * Requires to be in idle state, so the device can rely that the
             * host actually knows what device it's communicating with, and
             * actually means to update the firmware as next step here.
             */
            if (state == ST_HELLO) {
                state = ST_FWUPDATE;
                number_of_pages = rq->wValue.word;
#ifdef DEBUG
                uart_print("INIT: ");
                uart_putint(number_of_pages, 1);
                uart_print(" pages\r\n");
#else
                uart_print("FWUPDATE_INIT\r\n");
#endif
            }
            break;

        case CMD_FWUPDATE_MEMPAGE:
            /*
             * Receive a single memory page of the firmware data.
             * Requires to be in firmware update state.
             */
            if (state == ST_FWUPDATE) {
                recv_cnt = 0;
                recv_len = rq->wLength.word;
#ifdef DEBUG
                uart_print("MEMPAGE: ");
                uart_putint(recv_len, 1);
                uart_print(" bytes\r\n");
#else
                uart_print("FWUPDATE_MEMPAGE\r\n");
#endif
                /*
                 * Tell V-USB that there's more data transferred from the host.
                 * This will trigger the received data to be handled within
                 * the usbFunctionWrite() function.
                 */
                return USB_NO_MSG;
            }
            break;

        case CMD_FWUPDATE_VERIFY:
            /*
             * Verify the last transferred memory page data.
             * Sends the received data back to the host so it can compare it
             * and decide whether to move on or resend it again.
             * Requires to be in firmware update state.
             *
             * Note, this needs to be a receive request.
             */
            if (state == ST_FWUPDATE) {
                boot_rww_enable();
                repl_len = rq->wLength.word;
                repl_cnt = 0;
#ifdef DEBUG
                uart_print("VERIFY: page ");
                uart_putint(recv_data.page, 1);
                uart_print(" len ");
                uart_putint(repl_len, 1);
                uart_newline();
#else
                uart_print("FWUPDATE_VERIFY\r\n");
#endif

                /*
                 * Tell V-USB that there's more data transferred for the host.
                 * This will trigger calling the usbFunctionWrite() function.
                 */
                return USB_NO_MSG;
            }
            break;

        case CMD_FWUPDATE_FINALIZE:
            /*
             * Finalize firmware update.
             * Returns to idle state after this, so in order to send more
             * or new firmware data, the host has to start over from the
             * CMD_FWUPDATE_INIT request.
             */
            if (state == ST_FWUPDATE) {
                uart_print("FINALIZE\r\n");
                boot_rww_enable();
                state = ST_HELLO;
            }
            break;

        case CMD_BYE:
            /*
             * Go back to idle state
             */
            uart_print("BYE\r\n");
            state = ST_IDLE;
            break;

        case CMD_RESET:
            /*
             * Reset the device, but only if in idle state
             */
            if (state == ST_IDLE) {
                uart_print("\r\nRESET\r\n");
                state = ST_RESET;
            }
            break;
    }
    return 0;
}

/**
 * V-USB write callback function.
 *
 * Called when a send request has additional data to be written from the
 * host to the device. So this is really more a receive function here,
 * but since USB communication is always from the host's point of view,
 * it's a write operation. But we're receiving.
 *
 * In this case, we're receiving the data for single memory page that
 * will be become the device's new application firmware.
 */
uchar
usbFunctionWrite(uchar *data, uchar len)
{
    uint8_t i;
    uint8_t *recv_ptr = (uint8_t *) &recv_data;

    for (i = 0; recv_cnt < recv_len && i < len; i++, recv_cnt++) {
        recv_ptr[recv_cnt] = data[i];
    }

    if (recv_cnt == recv_len) {
        recv_all = 1;
        program();
    }

    return (recv_cnt == recv_len);
}

/**
 * V-USB read callback function.
 *
 * Called when the device sends data back to the host (again, it's a read
 * operation from the host's point of view, and therefore a send operation
 * from the device's point of view).
 *
 * Here, it's sending back the last written memory page so the host can
 * verify that all went okay.
 */
uchar
usbFunctionRead(uchar *data, uchar len)
{
    uint8_t i;
    uint16_t address = ((recv_data.page - 1) << 7) + repl_cnt;

    if (len > repl_len - repl_cnt) {
        len = repl_len - repl_cnt;
    }
    repl_cnt += len;
#ifdef DEBUG
    uart_print("read ");
#endif
    for (i = 0; i < len; i++) {
        *data = pgm_read_byte((void *) address++);
#ifdef DEBUG
        uart_puthex(*data);
#endif
        data++;
    }
#ifdef DEBUG
    uart_newline();
#endif

    return len;
}

/**
 * Write a single memory page to the device's flash.
 *
 * This performs the actual firmware update page by page.
 */
void
program(void)
{
    uint16_t address = (recv_data.page - 1) << 7;
    uint8_t i;
    uint8_t sreg;
    uint8_t *buf = (uint8_t *) &recv_data.data;

    sreg = SREG;
    boot_page_erase(address);
    boot_spm_busy_wait();

    for (i = 0; i < recv_data.size; i += 2) {
        uint16_t word = *buf++;
        word += (*buf++) << 8;
        boot_page_fill(address + i, word);
    }

    boot_page_write(address);
    boot_spm_busy_wait();

    SREG = sreg;
}

/**
 * Initialize watchdog.
 *
 * This is placed in the .init3 memory section and therefore called
 * long before main(). This makes sure the watchdog is disabled by
 * the time main() takes over (watchdog itself is enabled as part
 * of resetting the device to the application code)
 */
void
wdt_init(void)
{
    MCUSR=0;
    wdt_disable();
}

/*
 * Off we go..
 */
int
main(void)
{
    uint8_t i;
    uint8_t shutdown_counter = 0;
    uint8_t bootloader_enabled = 0;

    /* Set up LED I/O pin as output, low */
    PORTB &= ~(_BV(ws2812_pin));
    DDRB  |= _BV(ws2812_pin);

    /* Set up bootloader activation pin as input w/ pullup */
    BOOTLOADER_ENABLE_DDR &= ~(1 << BOOTLOADER_ENABLE_PIN);
    BOOTLOADER_ENABLE_PORT = (1 << BOOTLOADER_ENABLE_PIN);

    /* Turn off all LEDs */
    for (i = 0; i < NUM_LEDS; i++) {
        leds[i].r = 0;
        leds[i].g = 0;
        leds[i].b = 0;
    }
    ws2812_sendarray((uint8_t *) leds, NUM_LEDS * 3);

    /* Shift interrupt vector to bootloader space */
    MCUCR = (1 << IVCE);
    MCUCR = (1 << IVSEL);

    /* Read bootloader enable pin state to check if bootloader is enabled */
    bootloader_enabled = ((BOOTLOADER_ENABLE_PORT_IN & (1 << BOOTLOADER_ENABLE_PIN)) == 0);

    /* Print banner and bootloader activation pin state */
    uart_init(UART_BRATE_9600_12MHZ);
    uart_putchar('\f');
    uart_print((char *) banner);
    uart_newline();
    uart_print("Pin state: ");
    uart_putchar((bootloader_enabled) ? '1' : '0');
    uart_newline();
    
    /* Check input port if Bootloader button is pressed */
    if (!bootloader_enabled) {
        uart_newline();

        /* Nope. Put interrupt vector back in order... */
        MCUCR = (1 << IVCE);
        MCUCR = 0;
        /* ...delay a moment so UART can finish its output... */
        _delay_ms(1);
        /* ...and jump to application */
        asm("jmp 0000");
    }

    /* Yep, bootloader activated */
    uart_print("Welcome\r\n");

    /* Turn first LED on */
    leds[0].r = 0;
    leds[0].g = 0x10;
    leds[0].b = 0x20;
    ws2812_sendarray((uint8_t *) leds, 3);

    /* Force USB re-enumeration and set it up */
    usbDeviceDisconnect();
    _delay_ms(300);
    usbDeviceConnect();
    usbInit();

    sei();

    /* Get going */
    while (1) {
        usbPoll();
        if (state == ST_FWUPDATE) {
            if (recv_all) {
#ifdef DEBUG
                uart_print("page ");
                uart_putint(recv_data.page, 2);
                uart_print(" addr ");
                uart_putint((recv_data.page - 1) << 7, 5);
                uart_print(" with ");
                uart_putint(recv_data.size, 3);
                uart_print(" bytes: ");
                
                for (i = 0; i < SPM_PAGESIZE && i < recv_data.size; i++) {
                    if ((i & 0xf) == 0) {
                        uart_newline();
                    }
                    uart_puthex(recv_data.data[i]);
                    uart_putchar(' ');
                }
                uart_newline();
#endif
                recv_all = 0;
            }

        } else if (state == ST_RESET) {
            /*
             * Reset initiated, delay it for a few loop cycles to make sure
             * that USB communication properly finishes before disconeccting
             * so the other side won't get any pipe errors or the like.
             */
            if (++shutdown_counter == 10) {
                break;
            }
        } else {
            _delay_ms(10);
        }
    }

    usbDeviceDisconnect();

    cli();
    MCUCR = (1 << IVCE);
    MCUCR = 0;
    wdt_enable(WDTO_60MS);
    while (1);
}


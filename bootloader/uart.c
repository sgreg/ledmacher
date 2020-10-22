/*
 * Generic UART communication
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
#include <avr/io.h>
#include <avr/interrupt.h>
#include "uart.h"


/**
 * Initialize UART with given baud rate value.
 * See list of UART_BRATE_* defines for some predefined baud rate values.
 *
 * @param brate UART baud rate
 */
void
uart_init(int16_t brate)
{
    UBRR0H = (brate >> 8) & 0xff;
    UBRR0L = (brate     ) & 0xff;

    UCSR0B = (0 << RXCIE0)  /* disable RX available int */
           | (0 << TXCIE0)  /* disable TX done int */
           | (0 << UDRIE0)  /* disable data reg empty int */
           | (0 << RXEN0)   /* disable RX */
           | (1 << TXEN0)   /* enable TX */
           | (0 << UCSZ02)  /* no 9th data bit */
           | 0;             /* ignore rest */

    UCSR0C = (0 << UMSEL01) /* asynchronous mode */
           | (0 << UMSEL00) /*          "        */
           | (0 << UPM01)   /* no parity */
           | (0 << UPM00)   /*     "     */
           | (0 << USBS0)   /* 1 stop bit */
           | (1 << UCSZ01)  /* 8 data bits */
           | (1 << UCSZ00)  /*      "      */
           | (0 << UCPOL0); /* no polarity */
}


/**
 * Transmit a single character via UART.
 * @param data Character to write
 */
void
uart_putchar(char data)
{
    while (!(UCSR0A & (1 << UDRE0))) {
        /* wait for empty tx buffer */
    }
    UDR0 = data;
}


/**
 * Print a newline via UART.
 */
void
uart_newline(void)
{
    uart_putchar('\r');
    uart_putchar('\n');
}

/**
 * Print a given string via UART.
 * @param data String to print
 */
void
uart_print(char *data)
{
    while (*data) {
        uart_putchar(*data++);
    }
}


#ifdef DEBUG
/**
 * Print a given byte as hexadecimal value via UART.
 * Note, only single bytes are printed, also no 0x suffix is output
 * automatically, so add that if required manually.
 *
 * @param data Value to print as hexadecimal.
 */
void
uart_puthex(char data)
{
    static const char hexvals[] = "0123456789abcdef";
    uart_putchar(hexvals[(data >> 4) & 0x0f]);
    uart_putchar(hexvals[data & 0x0f]);
}


/**
 * Converts a given number digit by digit to a character buffer.
 * Note: the following limits are applied:
 * <ul>
 * <li>negative numbers are converted to positive numbers</li>
 * <li>base 10 conversion only</li>
 * <li>maximum number of 9 digits are converted</li>
 * </ul>
 *
 * @param number Number to be converted.
 * @param buf    Buffer the digits are put.
 * @return Number of digits converted.
 */
static uint8_t
tobuf(int32_t number, char *buf)
{
	int32_t next_digit;
	int8_t i;

	if (number < 0) {
		number *= -1;
	}

	for (i = 0; i < 10 && number; i++) {
		next_digit = (int32_t) (number / 10);
		buf[i] = ((char) (number - next_digit * 10)) + '0';
		number = next_digit;
	}
	return --i;
}


/**
 * Prints a given signed base 10 number via UART.
 * A number of minumum digits can be specified. If the given number has
 * less digits, the output is filled with leading zeros. If the number
 * has more digits, all digits are printed.
 * Note: this function uses the number_to_buf() method, therefore the
 * same limits apply here.
*
 * @param number Number to be printed.
 * @param digits Minimum number of digits to print.
 */
void
uart_putint(int32_t number, int8_t digits)
{
	char buf[10];
	int8_t i;

	if (number < 0) {
		uart_putchar('-');
		number *= -1;
	}

	i = tobuf(number, buf);

	while (i < --digits) {
		uart_putchar('0');
	}
	while (i >= 0) {
		uart_putchar(buf[i--]);
	}
}

#endif /* DEBUG */

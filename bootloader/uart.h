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
#ifndef _UART_H_
#define _UART_H_
#include <stdint.h>

/* baud rate values for U2Xn=0 */
#define UART_BRATE_9600_12MHZ   77
#define UART_BRATE_19200_12MHZ  38
#define UART_BRATE_38400_12MHZ  19
#define UART_BRATE_57600_12MHZ  12

/**
 * Initialize UART with given baud rate value.
 * See list of UART_BRATE_* defines for some predefined baud rate values.
 *
 * @param brate UART baud rate
 */
void uart_init(int16_t brate);

/**
 * Transmit a single character via UART.
 * @param data Character to write
 */
void uart_putchar(char data);

/**
 * Print a newline via UART.
 */
void uart_newline(void);

/**
 * Print a given string via UART.
 * @param data String to print
 */
void uart_print(char *data);

#ifdef DEBUG
/**
 * Print a given byte as hexadecimal value via UART.
 * Note, only single bytes are printed, also no 0x suffix is output
 * automatically, so add that if required manually.
 *
 * @param data Value to print as hexadecimal.
 */
void uart_puthex(char data);

/**
 * Print a given signed base 10 number via UART.
 * A number of minumum digits can be specified. If the given number has
 * less digits, the output is filled with leading zeros. If the number
 * has more digits, all digits are printed.
*
 * @param number Number to be printed.
 * @param digits Minimum number of digits to print.
 */
void uart_putint(int32_t number, int8_t digits);

#endif /* DEBUG */

#endif /* _UART_H_ */

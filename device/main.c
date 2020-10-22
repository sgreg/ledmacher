/*
 * Ledmacher Device Application - Main
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
#include <util/delay.h>
#include <avr/io.h>
#include <avr/interrupt.h>
#include "light_ws2812.h"
#include "created.h"

/** Array of all colors */
extern struct cRGB colors[];
/** Number of different colors */
static const uint8_t num_colors = (sizeof colors / sizeof *colors);

/** All the LED's current values */
static struct cRGB leds[NUM_LEDS];
/** Gradient target RGB value */
static struct cRGB gradient;
/** Gradient step value for each R, G, B component */
static struct cRGB step;

/** Index within the colors array */
static uint8_t color_index;
/** Keep track if a gradient process is currently ongoing or not */
static uint8_t gradient_ongoing;


/**
 * Get a single LED's updated value for an ongoing gradient process.
 *
 * The new value is determined based on the LED's current value, its
 * target value (i.e. the color it should have at the end of the ongoing
 * gradient process) and the step value determined in get_step().
 *
 * @param led LED color's current value
 * @param gradient LED color's target value
 * @param step LED color's gradient step increase value
 * @return LED color's new value
 */
uint8_t
led_value(uint8_t led, uint8_t gradient, uint8_t step)
{
    if (led > gradient) {
        if ((led - step) > led) {
            return 0;
        } else if ((led - step) < gradient) {
            return gradient;
        } else {
            return led - step;
        }
    } else if (led < gradient) {
        if ((led + step) < led) {
            return 0xff;
        } else if ((led + step) > gradient) {
            return gradient;
        } else {
            return led + step;
        }
    } else {
        return led;
    }
}

/**
 * Get a single gradient step value based on the given current value
 * and the given gradient target value in respect to the configured
 * GRADIENT_STEPS value.
 *
 * When setting up a new gradient, this function determines the step
 * value to get from a single R/G/B value to the gradient target value
 * in GRADIENT_STEPS amount of steps.
 *
 * @param led Current LED value, starting value for the next gradient
 * @param gradient Target gradient value
 * @return Single step value for each gradient step
 */
uint8_t
get_step(uint8_t led, uint8_t gradient)
{
    uint8_t step = 0;
    if (led > gradient) {
        step = ((led - gradient) / GRADIENT_STEPS);

    } else if (led < gradient) {
        step = ((led + gradient) / GRADIENT_STEPS);
    } else {
        // do nothing
        return 0;
    }

    return step > 0 ? step : 1;
}

/**
 * Perform a single gradient process step.
 *
 * Adjusts each LED's RGB value based on the step value.
 */
void
gradient_step(void)
{
    uint8_t i;

    uint8_t r = led_value(leds[0].r, gradient.r, step.r);
    uint8_t g = led_value(leds[0].g, gradient.g, step.g);
    uint8_t b = led_value(leds[0].b, gradient.b, step.b);

    for (i = 0; i < NUM_LEDS; i++) {
        leds[i].r = r;
        leds[i].g = g;
        leds[i].b = b;
    }
}

/**
 * Check if the gradient process is still ongoing by comparing the
 * LEDs' current RGB values with the gradient's target RGB values.
 *
 * @return 1 if gradient process is still ongoing and the LEDs haven't
 * reached their target gradient value yet, 0 if LEDs match gradient value.
 */
uint8_t
check_gradient_process(void)
{
    return !(leds[0].r == gradient.r &&
             leds[0].g == gradient.g &&
             leds[0].b == gradient.b);
}

/**
 * Kick off the next gradient process.
 *
 * Takes the next color from the configured color array and calculates
 * each R/G/B value's step value, i.e. the difference for each color to
 * get from the LED's current color to the target color.
 */
void
next_gradient(void)
{
    gradient.r = colors[color_index].r;
    gradient.g = colors[color_index].g;
    gradient.b = colors[color_index].b;

    step.r = get_step(leds[0].r, gradient.r);
    step.g = get_step(leds[0].g, gradient.g);
    step.b = get_step(leds[0].b, gradient.b);

    gradient_ongoing = 1;

    if (++color_index == num_colors) {
        color_index = 0;
    }
}

/*
 * Main
 */
int
main(void)
{
    uint8_t i;

    /* Set up LED GPIO */
	PORTB &= ~(_BV(ws2812_pin));
	DDRB  |= _BV(ws2812_pin);

    /* Default init all LEDs */
    for (i = 0; i < NUM_LEDS; i++) {
        leds[i].r = 0;
        leds[i].g = 0;
        leds[i].b = 0;
    }

    /* Start with first gradient right away */
    next_gradient();

    /* Loop on */
    while (1) {
        if (gradient_ongoing) {
            gradient_step();
            ws2812_sendarray((uint8_t *) leds, NUM_LEDS * 3);
            gradient_ongoing = check_gradient_process();
        } else {
            _delay_ms(WAIT_COLOR_MS);
            next_gradient();
        }

        _delay_ms(WAIT_GRADIENT_MS);
    }
}


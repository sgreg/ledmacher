#ifndef _FOO_H_
#define _FOO_H_

/* TODO get all these via FaaS value */
#define NUM_LEDS 16
#define WAIT_COLOR_MS 500
#define WAIT_GRADIENT_MS 10 /* practically, > 200ms is rather useless unless GRADIENT_STEPS is very small */
#define GRADIENT_STEPS 100 /* more than 100 is also quite pointless as get_step() will mostly cut them lower anyway */

struct cRGB colors[] = {
    { .r = 0x00, .g = 0xf0, .b = 0xf0 },
    { .r = 0x80, .g = 0x00, .b = 0xf0 },
    { .r = 0x00, .g = 0xc0, .b = 0x00 },
    { .r = 0xa0, .g = 0x60, .b = 0x00 },
    { .r = 0x30, .g = 0xf0, .b = 0x30 },
    { .r = 0xf0, .g = 0x30, .b = 0x00 },
};

#endif /* _FOO_H_ */

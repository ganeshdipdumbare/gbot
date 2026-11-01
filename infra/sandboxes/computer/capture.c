// gbot-capture: writes the X screen to stdout as a JPEG.
// A few dozen lines instead of ImageMagick keeps the computer image small.
#include <stdio.h>
#include <stdlib.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <jpeglib.h>

int main(int argc, char **argv) {
    int quality = argc > 1 ? atoi(argv[1]) : 72;
    Display *d = XOpenDisplay(NULL);
    if (!d) { fprintf(stderr, "cannot open display\n"); return 1; }
    Window root = DefaultRootWindow(d);
    XWindowAttributes a;
    XGetWindowAttributes(d, root, &a);
    XImage *img = XGetImage(d, root, 0, 0, a.width, a.height, AllPlanes, ZPixmap);
    if (!img) { fprintf(stderr, "cannot read screen\n"); return 1; }

    struct jpeg_compress_struct c;
    struct jpeg_error_mgr e;
    c.err = jpeg_std_error(&e);
    jpeg_create_compress(&c);
    jpeg_stdio_dest(&c, stdout);
    c.image_width = a.width;
    c.image_height = a.height;
    c.input_components = 3;
    c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    jpeg_start_compress(&c, TRUE);

    unsigned char *row = malloc(a.width * 3);
    unsigned long rm = img->red_mask, gm = img->green_mask, bm = img->blue_mask;
    int rs = __builtin_ctzl(rm), gs = __builtin_ctzl(gm), bs = __builtin_ctzl(bm);
    for (int y = 0; y < a.height; y++) {
        for (int x = 0; x < a.width; x++) {
            unsigned long p = XGetPixel(img, x, y);
            row[x * 3] = (p & rm) >> rs;
            row[x * 3 + 1] = (p & gm) >> gs;
            row[x * 3 + 2] = (p & bm) >> bs;
        }
        jpeg_write_scanlines(&c, &row, 1);
    }
    jpeg_finish_compress(&c);
    jpeg_destroy_compress(&c);
    free(row);
    XDestroyImage(img);
    XCloseDisplay(d);
    return 0;
}

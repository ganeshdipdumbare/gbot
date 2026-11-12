// gbot-capture: writes the X screen to stdout as a JPEG, or as a PNG with
// --png for computer use. A few dozen lines instead of ImageMagick keeps
// the computer image small.
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <jpeglib.h>
#include <png.h>

static int write_png(XImage *img, int width, int height,
                     unsigned long rm, unsigned long gm, unsigned long bm) {
    png_structp png = png_create_write_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    if (!png) return 1;
    png_infop info = png_create_info_struct(png);
    if (!info) { png_destroy_write_struct(&png, NULL); return 1; }
    if (setjmp(png_jmpbuf(png))) {
        png_destroy_write_struct(&png, &info);
        return 1;
    }
    png_init_io(png, stdout);
    png_set_IHDR(png, info, width, height, 8, PNG_COLOR_TYPE_RGB,
                 PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);
    png_write_info(png, info);

    unsigned char *row = malloc(width * 3);
    int rs = __builtin_ctzl(rm), gs = __builtin_ctzl(gm), bs = __builtin_ctzl(bm);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned long p = XGetPixel(img, x, y);
            row[x * 3] = (p & rm) >> rs;
            row[x * 3 + 1] = (p & gm) >> gs;
            row[x * 3 + 2] = (p & bm) >> bs;
        }
        png_write_row(png, row);
    }
    free(row);
    png_write_end(png, NULL);
    png_destroy_write_struct(&png, &info);
    return 0;
}

int main(int argc, char **argv) {
    int png = argc > 1 && strcmp(argv[1], "--png") == 0;
    int quality = (!png && argc > 1) ? atoi(argv[1]) : 72;
    Display *d = XOpenDisplay(NULL);
    if (!d) { fprintf(stderr, "cannot open display\n"); return 1; }
    Window root = DefaultRootWindow(d);
    XWindowAttributes a;
    XGetWindowAttributes(d, root, &a);
    XImage *img = XGetImage(d, root, 0, 0, a.width, a.height, AllPlanes, ZPixmap);
    if (!img) { fprintf(stderr, "cannot read screen\n"); return 1; }

    if (png) {
        int code = write_png(img, a.width, a.height, img->red_mask, img->green_mask, img->blue_mask);
        XDestroyImage(img);
        XCloseDisplay(d);
        if (code) { fprintf(stderr, "cannot write png\n"); return 1; }
        return 0;
    }

    // Optional output width (argv[2]): the stream is much smaller at
    // 1024x640, and clicks are normalized, so the scale doesn't matter.
    int outw = argc > 2 ? atoi(argv[2]) : 0;
    if (outw <= 0 || outw > a.width) outw = a.width;
    int outh = a.height * outw / a.width;
    if (outh <= 0) outh = a.height;

    struct jpeg_compress_struct c;
    struct jpeg_error_mgr e;
    c.err = jpeg_std_error(&e);
    jpeg_create_compress(&c);
    jpeg_stdio_dest(&c, stdout);
    c.image_width = outw;
    c.image_height = outh;
    c.input_components = 3;
    c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    jpeg_start_compress(&c, TRUE);

    unsigned char *row = malloc(outw * 3);
    unsigned long rm = img->red_mask, gm = img->green_mask, bm = img->blue_mask;
    int rs = __builtin_ctzl(rm), gs = __builtin_ctzl(gm), bs = __builtin_ctzl(bm);
    for (int y = 0; y < outh; y++) {
        int sy = (long)y * a.height / outh;
        for (int x = 0; x < outw; x++) {
            int sx = (long)x * a.width / outw;
            unsigned long p = XGetPixel(img, sx, sy);
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

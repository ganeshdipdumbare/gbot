// gbot-capture: writes the X screen to stdout as a JPEG, or as a PNG with
// --png for computer use. A few dozen lines instead of ImageMagick keeps
// the computer image small.
//
// --stream FPS [QUALITY] keeps running and writes a frame each time the
// screen changes, at most FPS a second, each as "GBF <bytes>\n" and the
// JPEG. One process for the whole stream instead of one exec per frame,
// which cost about 40 ms each and kept the picture at a stutter. It ends
// when stdin closes or nobody reads its output any more.
#include <poll.h>
#include <setjmp.h>
#include <signal.h>
#include <time.h>
#include <unistd.h>
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

// One row of the screen as RGB, scaled to outw. 32-bit pixels with the usual masks (Xvfb's
// default) are read straight from the image, the rest through XGetPixel.
static void rgb_row(XImage *img, int srcw, int srch, int outw, int outh, int y, unsigned char *row) {
    unsigned long rm = img->red_mask, gm = img->green_mask, bm = img->blue_mask;
    int sy = (long)y * srch / outh;
    if (img->bits_per_pixel == 32 && rm == 0xff0000 && gm == 0xff00 && bm == 0xff && img->byte_order == LSBFirst) {
        const unsigned char *src = (const unsigned char *)img->data + (long)sy * img->bytes_per_line;
        for (int x = 0; x < outw; x++) {
            const unsigned char *p = src + ((long)x * srcw / outw) * 4;
            row[x * 3] = p[2]; row[x * 3 + 1] = p[1]; row[x * 3 + 2] = p[0];
        }
        return;
    }
    int rs = __builtin_ctzl(rm), gs = __builtin_ctzl(gm), bs = __builtin_ctzl(bm);
    for (int x = 0; x < outw; x++) {
        unsigned long p = XGetPixel(img, (long)x * srcw / outw, sy);
        row[x * 3] = (p & rm) >> rs;
        row[x * 3 + 1] = (p & gm) >> gs;
        row[x * 3 + 2] = (p & bm) >> bs;
    }
}

// The screen as a JPEG in memory; the caller frees *out.
static int encode_jpeg(XImage *img, int w, int h, int outw, int quality, unsigned char **out, unsigned long *len) {
    int outh = h * outw / w;
    if (outh <= 0) outh = h;
    struct jpeg_compress_struct c;
    struct jpeg_error_mgr e;
    c.err = jpeg_std_error(&e);
    jpeg_create_compress(&c);
    *out = NULL; *len = 0;
    jpeg_mem_dest(&c, out, len);
    c.image_width = outw;
    c.image_height = outh;
    c.input_components = 3;
    c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    c.dct_method = JDCT_IFAST;
    jpeg_start_compress(&c, TRUE);
    unsigned char *row = malloc(outw * 3);
    for (int y = 0; y < outh; y++) {
        rgb_row(img, w, h, outw, outh, y, row);
        jpeg_write_scanlines(&c, &row, 1);
    }
    jpeg_finish_compress(&c);
    jpeg_destroy_compress(&c);
    free(row);
    return 0;
}

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

static int stream(Display *d, int fps, int quality) {
    if (fps < 1) fps = 1;
    if (fps > 60) fps = 60;
    double frame = 1000.0 / fps;
    Window root = DefaultRootWindow(d);
    unsigned char *prev = NULL;
    size_t prevLen = 0;
    for (;;) {
        double began = now_ms();
        // Stdin closing (the server side went away) is noticed while waiting below, so the
        // capture stops instead of lingering in the computer.
        XWindowAttributes a;
        if (!XGetWindowAttributes(d, root, &a)) return 1;
        XImage *img = XGetImage(d, root, 0, 0, a.width, a.height, AllPlanes, ZPixmap);
        if (!img) return 1;
        size_t n = (size_t)img->bytes_per_line * img->height;
        // A still screen sends nothing; the server repeats the last frame when it needs to.
        if (!prev || n != prevLen || memcmp(prev, img->data, n) != 0) {
            prev = realloc(prev, n);
            memcpy(prev, img->data, n);
            prevLen = n;
            unsigned char *jpg; unsigned long len;
            encode_jpeg(img, a.width, a.height, a.width, quality, &jpg, &len);
            if (printf("GBF %lu\n", len) < 0 || fwrite(jpg, 1, len, stdout) != len || fflush(stdout) != 0) {
                free(jpg); XDestroyImage(img); return 0;
            }
            free(jpg);
        }
        XDestroyImage(img);
        // Wait out the rest of the frame, still watching stdin for its end.
        for (double left; (left = frame - (now_ms() - began)) >= 1;) {
            struct pollfd w = { .fd = 0, .events = POLLIN };
            if (poll(&w, 1, (int)left) > 0) {
                char buf[64];
                if (read(0, buf, sizeof buf) <= 0) return 0;
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--stream") == 0) {
        signal(SIGPIPE, SIG_DFL);
        Display *d = XOpenDisplay(NULL);
        if (!d) { fprintf(stderr, "cannot open display\n"); return 1; }
        int code = stream(d, argc > 2 ? atoi(argv[2]) : 30, argc > 3 ? atoi(argv[3]) : 72);
        XCloseDisplay(d);
        return code;
    }
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
    for (int y = 0; y < outh; y++) {
        rgb_row(img, a.width, a.height, outw, outh, y, row);
        jpeg_write_scanlines(&c, &row, 1);
    }
    jpeg_finish_compress(&c);
    jpeg_destroy_compress(&c);
    free(row);
    XDestroyImage(img);
    XCloseDisplay(d);
    return 0;
}

import os
import zlib
import struct

def make_png(width, height, rgba_data):
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(rgba_data[y*width*4 : (y+1)*width*4])
    idat = zlib.compress(bytes(raw), 9)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b'')

class Canvas:
    def __init__(self, w=16, h=16, bg=(40, 42, 46, 255)):
        self.w = w
        self.h = h
        self.pixels = [[list(bg) for _ in range(w)] for _ in range(h)]

    def set(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.pixels[y][x] = list(color)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.pixels[y][x]
        return [0, 0, 0, 0]

    def rect(self, x1, y1, x2, y2, color):
        for y in range(max(0, y1), min(self.h, y2 + 1)):
            for x in range(max(0, x1), min(self.w, x2 + 1)):
                self.set(x, y, color)

    def frame(self, x1, y1, x2, y2, color):
        for x in range(x1, x2 + 1):
            self.set(x, y1, color)
            self.set(x, y2, color)
        for y in range(y1, y2 + 1):
            self.set(x1, y, color)
            self.set(x2, y, color)

    def to_bytes(self):
        buf = bytearray()
        for y in range(self.h):
            for x in range(self.w):
                buf.extend(self.pixels[y][x])
        return buf

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = make_png(self.w, self.h, self.to_bytes())
        with open(path, 'wb') as f:
            f.write(data)

OUT_DIR = "src/main/resources/assets/voltcraft/textures/block"

# Standard Palette definitions
C_CHASSIS_DARK   = (26, 28, 32, 255)
C_CHASSIS_MID    = (42, 45, 52, 255)
C_CHASSIS_LIGHT  = (68, 72, 80, 255)
C_CHASSIS_BEVEL  = (88, 92, 102, 255)
C_BOLT           = (170, 175, 185, 255)
C_BLACK          = (14, 15, 18, 255)
C_WHITE          = (240, 242, 248, 255)

# Electrical Port Theme Colors
# DC + (Red)
C_DC_PLUS_BG     = (185, 30, 30, 255)
C_DC_PLUS_BORDER = (235, 50, 50, 255)
C_DC_PLUS_TEXT   = (255, 245, 245, 255)

# DC - (Black / Dark Slate)
C_DC_MINUS_BG     = (20, 22, 26, 255)
C_DC_MINUS_BORDER = (75, 82, 95, 255)
C_DC_MINUS_TEXT   = (225, 235, 250, 255)

# AC Phase L (Amber / Orange / Flame)
C_AC_PHASE_BG     = (205, 95, 20, 255)
C_AC_PHASE_BORDER = (250, 140, 35, 255)
C_AC_PHASE_TEXT   = (255, 248, 220, 255)

# AC Neutral N (Electric Blue)
C_AC_NEUTRAL_BG     = (25, 85, 200, 255)
C_AC_NEUTRAL_BORDER = (60, 145, 255, 255)
C_AC_NEUTRAL_TEXT   = (225, 242, 255, 255)

# Directional Banner Colors
C_IN_BLUE_DARK   = (15, 45, 95, 255)
C_IN_BLUE_MID    = (28, 90, 180, 255)
C_IN_BLUE_BRIGHT = (55, 150, 255, 255)
C_IN_BLUE_LED    = (40, 215, 255, 255)

C_OUT_GREEN_DARK   = (15, 80, 35, 255)
C_OUT_GREEN_MID    = (28, 145, 60, 255)
C_OUT_GREEN_BRIGHT = (55, 205, 95, 255)
C_OUT_GREEN_LED    = (70, 255, 110, 255)

# Hardware Terminals
C_TERM_COPPER = (210, 115, 50, 255)
C_TERM_BRASS  = (220, 175, 45, 255)
C_TERM_SCREW  = (195, 200, 210, 255)

# Screen / Displays
C_LCD_BG         = (16, 26, 28, 255)
C_LCD_FRAME      = (35, 52, 58, 255)
C_LCD_CYAN       = (40, 230, 235, 255)
C_LCD_GREEN      = (80, 245, 100, 255)
C_LCD_AMBER      = (250, 180, 40, 255)

# Aluminum Frame
C_ALUM_LIGHT    = (195, 200, 208, 255)
C_ALUM_MID      = (160, 166, 175, 255)
C_ALUM_DARK     = (115, 120, 130, 255)

# Drawing Primitives
def draw_chassis(c, bg=C_CHASSIS_MID, border=C_CHASSIS_DARK):
    c.rect(0, 0, 15, 15, bg)
    c.frame(0, 0, 15, 15, border)
    c.set(0, 0, C_CHASSIS_BEVEL); c.set(15, 0, C_CHASSIS_BEVEL)
    c.set(0, 15, C_CHASSIS_DARK); c.set(15, 15, C_CHASSIS_DARK)
    for bx, by in [(1, 1), (14, 1), (1, 14), (14, 14)]:
        c.set(bx, by, C_BOLT)

def draw_plus(c, cx, cy, col=C_WHITE):
    c.set(cx, cy-1, col)
    c.set(cx-1, cy, col); c.set(cx, cy, col); c.set(cx+1, cy, col)
    c.set(cx, cy+1, col)

def draw_minus(c, cx, cy, col=C_WHITE):
    c.set(cx-1, cy, col); c.set(cx, cy, col); c.set(cx+1, cy, col)

def draw_char_L(c, cx, cy, col=C_WHITE):
    c.set(cx-1, cy-1, col)
    c.set(cx-1, cy, col)
    c.set(cx-1, cy+1, col); c.set(cx, cy+1, col); c.set(cx+1, cy+1, col)

def draw_char_N(c, cx, cy, col=C_WHITE):
    c.set(cx-1, cy-1, col); c.set(cx+1, cy-1, col)
    c.set(cx-1, cy, col);   c.set(cx, cy, col); c.set(cx+1, cy, col)
    c.set(cx-1, cy+1, col); c.set(cx+1, cy+1, col)

def draw_char_0(c, cx, cy, col=C_WHITE):
    c.set(cx-1, cy-1, col); c.set(cx, cy-1, col); c.set(cx+1, cy-1, col)
    c.set(cx-1, cy, col);                         c.set(cx+1, cy, col)
    c.set(cx-1, cy+1, col); c.set(cx, cy+1, col); c.set(cx+1, cy+1, col)

def draw_sine_icon(c, x, y, col=C_AC_PHASE_BORDER):
    c.set(x+1, y, col); c.set(x+2, y, col)
    c.set(x, y+1, col); c.set(x+3, y+1, col)

def draw_arrow_down(c, cx, cy, col):
    c.set(cx, cy+1, col)
    c.set(cx-1, cy, col); c.set(cx, cy, col); c.set(cx+1, cy, col)
    c.set(cx, cy-1, col)

def draw_arrow_up(c, cx, cy, col):
    c.set(cx, cy-1, col)
    c.set(cx-1, cy, col); c.set(cx, cy, col); c.set(cx+1, cy, col)
    c.set(cx, cy+1, col)

def draw_arrow_left(c, cx, cy, col):
    c.set(cx-1, cy, col)
    c.set(cx, cy-1, col); c.set(cx, cy, col); c.set(cx, cy+1, col)
    c.set(cx+1, cy, col)

def draw_arrow_right(c, cx, cy, col):
    c.set(cx+1, cy, col)
    c.set(cx, cy-1, col); c.set(cx, cy, col); c.set(cx, cy+1, col)
    c.set(cx-1, cy, col)

# Large prominent terminal blocks (5x5 on canvas)
def draw_terminal_block_dc_plus(c, x1, y1):
    c.rect(x1, y1, x1+4, y1+4, C_DC_PLUS_BG)
    c.frame(x1, y1, x1+4, y1+4, C_DC_PLUS_BORDER)
    c.set(x1+2, y1+2, C_TERM_SCREW)
    draw_plus(c, x1+2, y1+2, C_DC_PLUS_TEXT)

def draw_terminal_block_dc_minus(c, x1, y1):
    c.rect(x1, y1, x1+4, y1+4, C_DC_MINUS_BG)
    c.frame(x1, y1, x1+4, y1+4, C_DC_MINUS_BORDER)
    c.set(x1+2, y1+2, C_TERM_SCREW)
    draw_minus(c, x1+2, y1+2, C_DC_MINUS_TEXT)

def draw_terminal_block_ac_phase(c, x1, y1):
    c.rect(x1, y1, x1+4, y1+4, C_AC_PHASE_BG)
    c.frame(x1, y1, x1+4, y1+4, C_AC_PHASE_BORDER)
    draw_char_L(c, x1+2, y1+2, C_AC_PHASE_TEXT)

def draw_terminal_block_ac_neutral(c, x1, y1):
    c.rect(x1, y1, x1+4, y1+4, C_AC_NEUTRAL_BG)
    c.frame(x1, y1, x1+4, y1+4, C_AC_NEUTRAL_BORDER)
    draw_char_N(c, x1+2, y1+2, C_AC_NEUTRAL_TEXT)

# ==============================================================================
# 1. 4-TERMINAL CONVERTERS
# ==============================================================================

def make_converter_bottom(name):
    c = Canvas(16, 16, bg=C_CHASSIS_DARK)
    c.frame(0, 0, 15, 15, (18, 20, 24, 255))
    c.rect(2, 2, 13, 13, (34, 36, 40, 255))
    for fx, fy in [(1, 1), (13, 1), (1, 13), (13, 13)]:
        c.rect(fx, fy, fx+1, fy+1, (12, 12, 14, 255))
        c.set(fx, fy, (60, 64, 70, 255))
    c.rect(5, 5, 10, 10, (26, 28, 32, 255))
    c.frame(5, 5, 10, 10, (50, 54, 60, 255))
    c.set(7, 7, C_BOLT); c.set(8, 8, C_BOLT)
    c.save(f"{OUT_DIR}/{name}_bottom.png")

def make_converter_top(name, in_type, out_type, title_initials, color_accent):
    c = Canvas(16, 16, bg=C_CHASSIS_MID)
    draw_chassis(c)
    
    # BACK edge (y=0..2): Input 1 (+ or L)
    if in_type == 'DC':
        c.rect(5, 0, 10, 2, C_IN_BLUE_MID)
        c.frame(5, 0, 10, 2, C_IN_BLUE_BRIGHT)
        c.set(6, 1, C_DC_PLUS_BORDER)
        draw_plus(c, 8, 1, C_WHITE)
        draw_arrow_down(c, 10, 1, C_IN_BLUE_LED)
    else: # AC
        c.rect(5, 0, 10, 2, C_IN_BLUE_MID)
        c.frame(5, 0, 10, 2, C_IN_BLUE_BRIGHT)
        c.set(6, 1, C_AC_PHASE_BORDER)
        draw_char_L(c, 8, 1, C_WHITE)
        draw_arrow_down(c, 10, 1, C_IN_BLUE_LED)

    # LEFT edge (x=0..2): Input 2 (- or N)
    if in_type == 'DC':
        c.rect(0, 5, 2, 10, C_IN_BLUE_MID)
        c.frame(0, 5, 2, 10, C_IN_BLUE_BRIGHT)
        c.set(1, 6, C_DC_MINUS_BORDER)
        draw_minus(c, 1, 8, C_WHITE)
        draw_arrow_right(c, 1, 10, C_IN_BLUE_LED)
    else: # AC
        c.rect(0, 5, 2, 10, C_IN_BLUE_MID)
        c.frame(0, 5, 2, 10, C_IN_BLUE_BRIGHT)
        c.set(1, 6, C_AC_NEUTRAL_BORDER)
        draw_char_N(c, 1, 8, C_WHITE)
        draw_arrow_right(c, 1, 10, C_IN_BLUE_LED)

    # FRONT edge (y=13..15): Output 1 (+ or L)
    if out_type == 'DC':
        c.rect(5, 13, 10, 15, C_OUT_GREEN_MID)
        c.frame(5, 13, 10, 15, C_OUT_GREEN_BRIGHT)
        draw_arrow_down(c, 6, 14, C_OUT_GREEN_LED)
        draw_plus(c, 8, 14, C_WHITE)
        c.set(10, 14, C_DC_PLUS_BORDER)
    elif out_type == 'AC':
        c.rect(5, 13, 10, 15, C_OUT_GREEN_MID)
        c.frame(5, 13, 10, 15, C_OUT_GREEN_BRIGHT)
        draw_arrow_down(c, 6, 14, C_OUT_GREEN_LED)
        draw_char_L(c, 8, 14, C_WHITE)
        c.set(10, 14, C_AC_PHASE_BORDER)
    else: # EU output coupling
        c.rect(5, 13, 10, 15, (160, 30, 30, 255))
        c.frame(5, 13, 10, 15, (220, 50, 50, 255))
        draw_arrow_down(c, 8, 14, (255, 215, 0, 255))

    # RIGHT edge (x=13..15): Output 2 (- or N)
    if out_type == 'DC':
        c.rect(13, 5, 15, 10, C_OUT_GREEN_MID)
        c.frame(13, 5, 15, 10, C_OUT_GREEN_BRIGHT)
        draw_arrow_right(c, 14, 6, C_OUT_GREEN_LED)
        draw_minus(c, 14, 8, C_WHITE)
        c.set(14, 10, C_DC_MINUS_BORDER)
    elif out_type == 'AC':
        c.rect(13, 5, 15, 10, C_OUT_GREEN_MID)
        c.frame(13, 5, 15, 10, C_OUT_GREEN_BRIGHT)
        draw_arrow_right(c, 14, 6, C_OUT_GREEN_LED)
        draw_char_N(c, 14, 8, C_WHITE)
        c.set(14, 10, C_AC_NEUTRAL_BORDER)
    else: # EU side vent / coupling
        c.rect(13, 5, 15, 10, (140, 25, 25, 255))
        c.frame(13, 5, 15, 10, (200, 45, 45, 255))

    # Center: Ventilation louvers & emblem
    c.rect(4, 4, 11, 11, C_CHASSIS_DARK)
    c.frame(4, 4, 11, 11, C_CHASSIS_BEVEL)
    for ly in [5, 7, 9]:
        c.rect(5, ly, 10, ly, (18, 19, 22, 255))
        c.rect(5, ly+1, 10, ly+1, (55, 58, 65, 255))
    c.set(7, 5, color_accent); c.set(8, 5, color_accent)
    c.save(f"{OUT_DIR}/{name}_top.png")

def make_converter_front(name, out_type, title):
    c = Canvas(16, 16, bg=C_CHASSIS_MID)
    draw_chassis(c)
    
    # Top banner: Green OUT
    c.rect(2, 1, 13, 3, C_OUT_GREEN_DARK)
    c.frame(2, 1, 13, 3, C_OUT_GREEN_MID)
    c.set(7, 2, C_OUT_GREEN_LED); c.set(8, 2, C_OUT_GREEN_LED)
    c.set(3, 2, C_WHITE); c.set(4, 2, C_WHITE)
    c.set(11, 2, C_WHITE); c.set(12, 2, C_WHITE)

    # Center display (y=4..8)
    c.rect(2, 4, 13, 8, C_LCD_BG)
    c.frame(2, 4, 13, 8, C_LCD_FRAME)
    
    if out_type == 'DC':
        c.rect(3, 5, 12, 7, (12, 20, 22, 255))
        c.set(3, 5, C_LCD_CYAN); c.set(4, 5, C_LCD_CYAN)
        c.set(3, 6, C_LCD_CYAN); c.set(4, 6, C_LCD_CYAN)
        c.set(3, 7, C_LCD_CYAN); c.set(4, 7, C_LCD_CYAN)
        c.rect(6, 6, 11, 6, C_LCD_GREEN)
        c.set(9, 5, C_LCD_GREEN); c.set(11, 7, C_LCD_GREEN)
    elif out_type == 'AC':
        draw_sine_icon(c, 3, 5, C_LCD_AMBER)
        draw_sine_icon(c, 8, 5, C_LCD_AMBER)
        c.set(12, 5, C_LCD_CYAN); c.set(12, 6, C_LCD_CYAN); c.set(12, 7, C_LCD_CYAN)
    elif out_type == 'EU':
        c.rect(4, 5, 11, 7, (35, 15, 15, 255))
        c.set(6, 6, (255, 200, 50, 255)); c.set(9, 6, (255, 200, 50, 255))
        c.set(7, 5, (255, 200, 50, 255)); c.set(8, 7, (255, 200, 50, 255))

    # Bottom Port: Output Terminal 1 (x=5..10, y=9..14)
    c.rect(4, 9, 11, 14, C_OUT_GREEN_DARK)
    c.frame(4, 9, 11, 14, C_OUT_GREEN_BRIGHT)
    
    if out_type == 'DC':
        draw_terminal_block_dc_plus(c, 5, 10)
        c.set(11, 11, C_DC_PLUS_BORDER); c.set(11, 13, C_DC_PLUS_BORDER)
        c.set(4, 11, C_OUT_GREEN_LED)
    elif out_type == 'AC':
        draw_terminal_block_ac_phase(c, 5, 10)
        draw_sine_icon(c, 11, 10, C_AC_PHASE_BORDER)
        c.set(4, 11, C_OUT_GREEN_LED)
    else: # EU output socket
        c.rect(5, 10, 10, 13, (160, 30, 30, 255))
        c.frame(5, 10, 10, 13, (240, 60, 60, 255))
        c.set(7, 11, (255, 215, 0, 255)); c.set(8, 11, (255, 215, 0, 255))
        c.set(7, 12, (255, 215, 0, 255)); c.set(8, 12, (255, 215, 0, 255))

    c.save(f"{OUT_DIR}/{name}_front.png")

def make_converter_back(name, in_type):
    c = Canvas(16, 16, bg=C_CHASSIS_MID)
    draw_chassis(c)
    
    # Top banner: Blue IN
    c.rect(2, 1, 13, 3, C_IN_BLUE_DARK)
    c.frame(2, 1, 13, 3, C_IN_BLUE_MID)
    c.set(7, 2, C_IN_BLUE_LED); c.set(8, 2, C_IN_BLUE_LED)
    c.set(3, 2, C_WHITE); c.set(4, 2, C_WHITE)
    c.set(11, 2, C_WHITE); c.set(12, 2, C_WHITE)

    # Center technical spec & ventilation
    c.rect(2, 4, 13, 8, C_CHASSIS_DARK)
    c.frame(2, 4, 13, 8, C_CHASSIS_BEVEL)
    for y in [5, 7]:
        c.rect(3, y, 12, y, (15, 16, 18, 255))
    c.set(7, 5, (235, 190, 30, 255)); c.set(8, 5, (235, 190, 30, 255))
    c.set(7, 6, (235, 190, 30, 255)); c.set(8, 6, (235, 190, 30, 255))
    c.set(7, 7, (20, 20, 20, 255));   c.set(8, 7, (20, 20, 20, 255))

    # Bottom Port: Input Terminal 1
    c.rect(4, 9, 11, 14, C_IN_BLUE_DARK)
    c.frame(4, 9, 11, 14, C_IN_BLUE_BRIGHT)
    
    if in_type == 'DC':
        draw_terminal_block_dc_plus(c, 5, 10)
        c.set(11, 11, C_DC_PLUS_BORDER); c.set(11, 13, C_DC_PLUS_BORDER)
        c.set(4, 11, C_IN_BLUE_LED)
    else: # AC
        draw_terminal_block_ac_phase(c, 5, 10)
        draw_sine_icon(c, 11, 10, C_AC_PHASE_BORDER)
        c.set(4, 11, C_IN_BLUE_LED)

    c.save(f"{OUT_DIR}/{name}_back.png")

def make_converter_left(name, in_type):
    c = Canvas(16, 16, bg=C_CHASSIS_DARK)
    for x in range(1, 15, 2):
        c.rect(x, 1, x, 7, (18, 19, 22, 255))
        c.rect(x+1, 1, x+1, 7, (52, 56, 62, 255))
    c.frame(0, 0, 15, 15, C_CHASSIS_MID)

    # Blue IN Banner
    c.rect(2, 8, 13, 9, C_IN_BLUE_DARK)
    c.set(7, 8, C_IN_BLUE_LED); c.set(8, 8, C_IN_BLUE_LED)

    # Bottom Port: Input Terminal 2
    c.rect(4, 10, 11, 15, C_IN_BLUE_DARK)
    c.frame(4, 10, 11, 15, C_IN_BLUE_BRIGHT)
    
    if in_type == 'DC':
        draw_terminal_block_dc_minus(c, 5, 10)
        c.set(11, 11, C_DC_MINUS_BORDER); c.set(11, 13, C_DC_MINUS_BORDER)
        draw_minus(c, 3, 12, C_DC_MINUS_TEXT)
    else: # AC
        draw_terminal_block_ac_neutral(c, 5, 10)
        draw_char_0(c, 2, 12, C_AC_NEUTRAL_TEXT)
        c.set(11, 11, C_AC_NEUTRAL_BORDER)

    c.save(f"{OUT_DIR}/{name}_left.png")

def make_converter_right(name, out_type):
    c = Canvas(16, 16, bg=C_CHASSIS_DARK)
    for x in range(1, 15, 2):
        c.rect(x, 1, x, 7, (18, 19, 22, 255))
        c.rect(x+1, 1, x+1, 7, (52, 56, 62, 255))
    c.frame(0, 0, 15, 15, C_CHASSIS_MID)

    # Green OUT Banner
    c.rect(2, 8, 13, 9, C_OUT_GREEN_DARK)
    c.set(7, 8, C_OUT_GREEN_LED); c.set(8, 8, C_OUT_GREEN_LED)

    # Bottom Port: Output Terminal 2
    c.rect(4, 10, 11, 15, C_OUT_GREEN_DARK)
    c.frame(4, 10, 11, 15, C_OUT_GREEN_BRIGHT)
    
    if out_type == 'DC':
        draw_terminal_block_dc_minus(c, 5, 10)
        c.set(11, 11, C_DC_MINUS_BORDER); c.set(11, 13, C_DC_MINUS_BORDER)
        draw_minus(c, 3, 12, C_DC_MINUS_TEXT)
    elif out_type == 'AC':
        draw_terminal_block_ac_neutral(c, 5, 10)
        draw_char_0(c, 2, 12, C_AC_NEUTRAL_TEXT)
        c.set(11, 11, C_AC_NEUTRAL_BORDER)
    else: # EU output
        c.rect(5, 10, 10, 13, (160, 30, 30, 255))
        c.frame(5, 10, 10, 13, (240, 60, 60, 255))

    c.save(f"{OUT_DIR}/{name}_right.png")

def make_converter_side(name, in_type, out_type):
    c = Canvas(16, 16, bg=C_CHASSIS_DARK)
    for x in range(1, 15, 2):
        c.rect(x, 1, x, 14, (18, 19, 22, 255))
        c.rect(x+1, 1, x+1, 14, (55, 58, 65, 255))
    c.frame(0, 0, 15, 15, C_CHASSIS_MID)
    c.rect(0, 6, 15, 9, C_CHASSIS_MID)
    c.frame(0, 6, 15, 9, C_CHASSIS_BEVEL)
    c.set(2, 7, C_IN_BLUE_LED); c.set(2, 8, C_IN_BLUE_MID)
    c.set(13, 7, C_OUT_GREEN_LED); c.set(13, 8, C_OUT_GREEN_MID)
    c.save(f"{OUT_DIR}/{name}_side.png")

def generate_converter(name, in_type, out_type, title, accent_col):
    make_converter_front(name, out_type, title)
    make_converter_back(name, in_type)
    make_converter_left(name, in_type)
    make_converter_right(name, out_type)
    make_converter_top(name, in_type, out_type, title, accent_col)
    make_converter_bottom(name)
    make_converter_side(name, in_type, out_type)

# ==============================================================================
# 2. GENERATORS (Hand Crank, Portable Inverter)
# ==============================================================================

def make_generator_hand_crank():
    # FRONT (North): DC - Output Terminal & Voltmeter
    front = Canvas(16, 16, bg=C_CHASSIS_MID)
    draw_chassis(front)
    # Circular analog dial
    front.rect(4, 2, 11, 7, (230, 232, 235, 255))
    front.frame(4, 2, 11, 7, (70, 72, 78, 255))
    front.rect(5, 6, 10, 6, (200, 30, 30, 255))
    front.set(8, 4, (20, 20, 20, 255))
    front.set(7, 3, (20, 20, 20, 255))
    # North terminal: DC -
    front.rect(4, 9, 11, 14, C_OUT_GREEN_DARK)
    front.frame(4, 9, 11, 14, C_OUT_GREEN_BRIGHT)
    draw_terminal_block_dc_minus(front, 5, 10)
    front.set(4, 11, C_OUT_GREEN_LED)
    front.save(f"{OUT_DIR}/generator_hand_crank_front.png")

    # BACK (South): DC + Output Terminal & Stator Windings
    back = Canvas(16, 16, bg=C_CHASSIS_MID)
    draw_chassis(back)
    # Stator copper coils
    back.rect(4, 2, 11, 7, C_TERM_COPPER)
    back.frame(4, 2, 11, 7, (85, 88, 92, 255))
    for y in [3, 5]:
        back.rect(5, y, 10, y, (145, 75, 30, 255))
    # South terminal: DC +
    back.rect(4, 9, 11, 14, C_OUT_GREEN_DARK)
    back.frame(4, 9, 11, 14, C_OUT_GREEN_BRIGHT)
    draw_terminal_block_dc_plus(back, 5, 10)
    back.set(4, 11, C_OUT_GREEN_LED)
    back.save(f"{OUT_DIR}/generator_hand_crank_back.png")

    # TOP: Brass crank hub & spindle
    top = Canvas(16, 16, bg=(55, 57, 62, 255))
    top.frame(0, 0, 15, 15, C_CHASSIS_DARK)
    # North - and South + arrows
    draw_minus(top, 8, 1, C_DC_MINUS_TEXT)
    draw_arrow_up(top, 8, 3, C_OUT_GREEN_LED)
    draw_plus(top, 8, 14, C_DC_PLUS_TEXT)
    draw_arrow_down(top, 8, 12, C_OUT_GREEN_LED)
    # Center brass gear hub
    top.rect(5, 5, 10, 10, C_TERM_BRASS)
    top.frame(5, 5, 10, 10, (140, 110, 30, 255))
    top.rect(7, 7, 8, 8, C_BOLT)
    top.save(f"{OUT_DIR}/generator_hand_crank_top.png")

    # SIDE & BOTTOM
    side = Canvas(16, 16, bg=(48, 50, 54, 255))
    side.frame(0, 0, 15, 15, (30, 32, 34, 255))
    side.rect(3, 4, 12, 11, (36, 38, 42, 255))
    side.save(f"{OUT_DIR}/generator_hand_crank_side.png")

    bottom = Canvas(16, 16, bg=(35, 36, 38, 255))
    for bx, by in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        bottom.rect(bx-1, by-1, bx, by, (15, 15, 16, 255))
    bottom.save(f"{OUT_DIR}/generator_hand_crank_bottom.png")

def make_generator_portable_inverter():
    C_GEN_RED = (185, 35, 30, 255)
    C_GEN_RED_DARK = (135, 22, 18, 255)

    # FRONT (South terminal: AC Phase L)
    front = Canvas(16, 16, bg=C_GEN_RED)
    front.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    front.rect(2, 2, 13, 13, (28, 30, 34, 255))
    front.frame(2, 2, 13, 13, (55, 58, 64, 255))
    # AC Schuko Socket (L + N)
    front.rect(3, 3, 9, 8, C_OUT_GREEN_DARK)
    front.rect(4, 4, 8, 7, (18, 20, 22, 255))
    front.set(5, 5, C_TERM_BRASS) # L pin hole
    front.set(7, 5, C_TERM_BRASS) # N pin hole
    front.set(6, 4, C_BOLT); front.set(6, 7, C_BOLT) # ground
    # Phase L terminal marker
    draw_char_L(front, 11, 4, C_AC_PHASE_TEXT)
    # Neutral N terminal marker
    draw_char_N(front, 11, 8, C_AC_NEUTRAL_TEXT)
    # Status LEDs: Green Run, Red Overload, Amber Low Oil
    front.set(4, 11, (60, 240, 80, 255))
    front.set(6, 11, (240, 40, 40, 255))
    front.set(8, 11, (240, 180, 20, 255))
    front.save(f"{OUT_DIR}/generator_portable_inverter_front.png")

    # BACK (North terminal: AC Neutral N)
    back = Canvas(16, 16, bg=C_GEN_RED)
    back.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    back.rect(4, 4, 11, 11, (32, 34, 38, 255))
    back.frame(4, 4, 11, 11, (65, 68, 75, 255))
    back.rect(6, 3, 9, 4, (15, 15, 15, 255))
    back.rect(7, 5, 8, 8, (215, 218, 225, 255))
    # Neutral terminal port
    draw_terminal_block_ac_neutral(back, 5, 10)
    back.save(f"{OUT_DIR}/generator_portable_inverter_back.png")

    # TOP: Handle, fuel cap, and North N / South L indicators
    top = Canvas(16, 16, bg=C_GEN_RED)
    top.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    draw_char_N(top, 7, 2, C_AC_NEUTRAL_TEXT)
    draw_arrow_up(top, 9, 2, C_AC_NEUTRAL_BORDER)
    draw_char_L(top, 7, 13, C_AC_PHASE_TEXT)
    draw_arrow_down(top, 9, 13, C_AC_PHASE_BORDER)
    top.rect(6, 4, 9, 11, (30, 32, 36, 255))
    top.frame(6, 4, 9, 11, (60, 64, 70, 255))
    top.rect(2, 5, 4, 8, (20, 20, 22, 255))
    top.set(3, 6, (200, 160, 30, 255))
    top.save(f"{OUT_DIR}/generator_portable_inverter_top.png")

    # SIDE & BOTTOM
    side = Canvas(16, 16, bg=C_GEN_RED)
    side.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    side.rect(8, 3, 14, 9, (45, 48, 52, 255))
    side.frame(8, 3, 14, 9, (80, 85, 92, 255))
    side.rect(10, 5, 12, 7, (20, 22, 24, 255))
    side.set(11, 6, (10, 10, 12, 255))
    for y in [4, 6, 8, 10, 12]:
        side.rect(2, y, 6, y, (25, 27, 30, 255))
    side.save(f"{OUT_DIR}/generator_portable_inverter_side.png")

    bottom = Canvas(16, 16, bg=(25, 26, 28, 255))
    for bx, by in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        bottom.rect(bx-1, by-1, bx, by, (12, 12, 14, 255))
    bottom.rect(7, 7, 8, 8, C_BOLT)
    bottom.save(f"{OUT_DIR}/generator_portable_inverter_bottom.png")

# ==============================================================================
# 3. SOLAR PANELS
# ==============================================================================

def make_solar_panels():
    # Common helper for PV underside junction box
    def draw_solar_bottom(c, name):
        c.frame(0, 0, 15, 15, C_ALUM_DARK)
        # Center junction box (x=4..11, y=4..11)
        c.rect(4, 4, 11, 11, (22, 24, 28, 255))
        c.frame(4, 4, 11, 11, (55, 60, 68, 255))
        # North terminal: DC - (Black with minus)
        c.rect(5, 5, 10, 7, C_DC_MINUS_BG)
        c.frame(5, 5, 10, 7, C_DC_MINUS_BORDER)
        draw_minus(c, 8, 6, C_WHITE)
        # South terminal: DC + (Red with plus)
        c.rect(5, 8, 10, 10, C_DC_PLUS_BG)
        c.frame(5, 8, 10, 10, C_DC_PLUS_BORDER)
        draw_plus(c, 8, 9, C_WHITE)
        c.save(f"{OUT_DIR}/{name}_bottom.png")

    # 1. Monocrystalline
    mono_top = Canvas(16, 16, bg=(24, 26, 32, 255))
    mono_top.frame(0, 0, 15, 15, C_ALUM_MID)
    # North - and South + edge indicators
    mono_top.set(8, 0, C_DC_MINUS_BORDER)
    mono_top.set(8, 15, C_DC_PLUS_BORDER)
    for cx, cy in [(1, 1), (8, 1), (1, 8), (8, 8)]:
        mono_top.rect(cx, cy, cx+6, cy+6, (20, 23, 30, 255))
        mono_top.set(cx, cy, (35, 38, 45, 255)); mono_top.set(cx+6, cy, (35, 38, 45, 255))
        mono_top.set(cx, cy+6, (35, 38, 45, 255)); mono_top.set(cx+6, cy+6, (35, 38, 45, 255))
    mono_top.rect(4, 1, 4, 14, (190, 195, 205, 255))
    mono_top.rect(11, 1, 11, 14, (190, 195, 205, 255))
    mono_top.save(f"{OUT_DIR}/solar_panel_monocrystalline_top.png")

    mono_bottom = Canvas(16, 16, bg=(35, 37, 40, 255))
    draw_solar_bottom(mono_bottom, "solar_panel_monocrystalline")

    mono_side = Canvas(16, 16, bg=C_ALUM_MID)
    mono_side.rect(0, 0, 15, 1, C_ALUM_LIGHT)
    mono_side.rect(0, 5, 15, 7, C_ALUM_DARK)
    mono_side.rect(0, 14, 15, 15, (95, 100, 108, 255))
    mono_side.save(f"{OUT_DIR}/solar_panel_monocrystalline_side.png")

    # 2. Polycrystalline
    poly_top = Canvas(16, 16, bg=(25, 60, 130, 255))
    poly_top.frame(0, 0, 15, 15, C_ALUM_MID)
    poly_top.set(8, 0, C_DC_MINUS_BORDER)
    poly_top.set(8, 15, C_DC_PLUS_BORDER)
    flakes = [
        (2, 2, 4, 4, (35, 80, 170, 255)), (5, 2, 7, 5, (20, 50, 115, 255)),
        (8, 2, 11, 4, (30, 70, 150, 255)), (12, 2, 14, 5, (40, 85, 180, 255)),
        (2, 8, 5, 11, (35, 75, 165, 255)), (8, 8, 11, 10, (40, 90, 185, 255)),
        (8, 11, 14, 14, (35, 80, 170, 255))
    ]
    for x1, y1, x2, y2, col in flakes:
        poly_top.rect(x1, y1, x2, y2, col)
    poly_top.rect(4, 1, 4, 14, (205, 210, 220, 255))
    poly_top.rect(11, 1, 11, 14, (205, 210, 220, 255))
    poly_top.save(f"{OUT_DIR}/solar_panel_polycrystalline_top.png")

    poly_bottom = Canvas(16, 16, bg=(220, 225, 230, 255))
    draw_solar_bottom(poly_bottom, "solar_panel_polycrystalline")

    poly_side = Canvas(16, 16, bg=C_ALUM_MID)
    poly_side.rect(0, 0, 15, 1, C_ALUM_LIGHT)
    poly_side.rect(0, 5, 15, 7, C_ALUM_DARK)
    poly_side.save(f"{OUT_DIR}/solar_panel_polycrystalline_side.png")

    # 3. Thin-Film CdTe
    thin_top = Canvas(16, 16, bg=(28, 22, 26, 255))
    thin_top.frame(0, 0, 15, 15, (45, 40, 44, 255))
    thin_top.set(8, 0, C_DC_MINUS_BORDER)
    thin_top.set(8, 15, C_DC_PLUS_BORDER)
    for x in [3, 6, 9, 12]:
        thin_top.rect(x, 1, x, 14, (40, 32, 36, 255))
    thin_top.save(f"{OUT_DIR}/solar_panel_thin_film_top.png")

    thin_bottom = Canvas(16, 16, bg=(20, 20, 22, 255))
    draw_solar_bottom(thin_bottom, "solar_panel_thin_film")

    thin_side = Canvas(16, 16, bg=(38, 42, 45, 255))
    thin_side.rect(0, 0, 15, 2, (70, 75, 80, 255))
    thin_side.rect(0, 14, 15, 15, (25, 27, 30, 255))
    thin_side.save(f"{OUT_DIR}/solar_panel_thin_film_side.png")

    # 4. Concentrator CPV
    cpv_top = Canvas(16, 16, bg=(210, 155, 30, 255))
    cpv_top.frame(0, 0, 15, 15, C_ALUM_LIGHT)
    cpv_top.set(8, 0, C_DC_MINUS_BORDER)
    cpv_top.set(8, 15, C_DC_PLUS_BORDER)
    for cx, cy in [(2, 2), (9, 2), (2, 9), (9, 9)]:
        cpv_top.rect(cx, cy, cx+4, cy+4, (245, 195, 60, 255))
        cpv_top.frame(cx, cy, cx+4, cy+4, (185, 130, 20, 255))
        cpv_top.set(cx+2, cy+2, (255, 240, 150, 255))
    cpv_top.save(f"{OUT_DIR}/solar_panel_concentrator_top.png")

    cpv_bottom = Canvas(16, 16, bg=(60, 64, 70, 255))
    draw_solar_bottom(cpv_bottom, "solar_panel_concentrator")

    cpv_side = Canvas(16, 16, bg=(55, 58, 64, 255))
    for y in range(2, 14, 3):
        cpv_side.rect(0, y, 15, y, (30, 32, 36, 255))
        cpv_side.rect(0, y+1, 15, y+1, (80, 85, 95, 255))
    cpv_side.save(f"{OUT_DIR}/solar_panel_concentrator_side.png")

# ==============================================================================
# 4. BATTERIES (BESS)
# ==============================================================================

def make_battery_block(name, theme_col, chem_title, is_rack=False):
    # TOP: North terminal DC -, South terminal DC +
    top = Canvas(16, 16, bg=theme_col)
    top.frame(0, 0, 15, 15, (theme_col[0]//2, theme_col[1]//2, theme_col[2]//2, 255))
    
    # NORTH terminal: DC - (Black block with minus)
    draw_terminal_block_dc_minus(top, 5, 1)
    top.rect(3, 1, 4, 3, C_DC_MINUS_BORDER)
    top.rect(11, 1, 12, 3, C_DC_MINUS_BORDER)

    # SOUTH terminal: DC + (Red block with plus)
    draw_terminal_block_dc_plus(top, 5, 10)
    top.rect(3, 12, 4, 14, C_DC_PLUS_BORDER)
    top.rect(11, 12, 12, 14, C_DC_PLUS_BORDER)

    # Center: Diagnostic BMS display & chemistry
    top.rect(3, 6, 12, 9, C_LCD_BG)
    top.frame(3, 6, 12, 9, C_LCD_FRAME)
    # Status LEDs: Green Run, Amber Balance
    top.set(5, 7, C_LCD_GREEN); top.set(6, 7, C_LCD_GREEN)
    top.set(9, 7, C_LCD_AMBER); top.set(10, 7, C_LCD_AMBER)
    top.save(f"{OUT_DIR}/{name}_top.png")

    # SIDE: Industrial casing with handles & specs
    side = Canvas(16, 16, bg=theme_col)
    side.frame(0, 0, 15, 15, (theme_col[0]//2, theme_col[1]//2, theme_col[2]//2, 255))
    # Handles on sides
    side.rect(1, 6, 2, 9, (20, 22, 25, 255))
    side.rect(13, 6, 14, 9, (20, 22, 25, 255))
    # Hazard / warning stripes
    side.rect(3, 13, 12, 14, (30, 32, 36, 255))
    for x in [3, 6, 9, 12]:
        side.set(x, 13, (240, 190, 20, 255))
        side.set(x+1, 14, (240, 190, 20, 255))
    # Spec plaque
    side.rect(4, 3, 11, 8, (28, 30, 34, 255))
    side.frame(4, 3, 11, 8, (55, 60, 68, 255))
    # Draw chemistry text on plaque
    side.rect(5, 4, 10, 5, C_WHITE)
    side.rect(5, 6, 10, 7, (180, 185, 195, 255))
    side.save(f"{OUT_DIR}/{name}_side.png")
    side.save(f"{OUT_DIR}/{name}.png") # base inventory item icon

    # BOTTOM: Heavy base
    bottom = Canvas(16, 16, bg=(22, 24, 28, 255))
    bottom.frame(0, 0, 15, 15, (14, 15, 18, 255))
    for bx, by in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        bottom.rect(bx-1, by-1, bx, by, (10, 10, 12, 255))
    bottom.save(f"{OUT_DIR}/{name}_bottom.png")

# ==============================================================================
# 5. CREATIVE BLOCKS
# ==============================================================================

def make_creative_blocks():
    # Creative Generator
    cg_top = Canvas(16, 16, bg=(45, 20, 60, 255))
    cg_top.frame(0, 0, 15, 15, (95, 35, 130, 255))
    # North terminal: - / N
    cg_top.rect(4, 1, 11, 4, C_DC_MINUS_BG)
    cg_top.frame(4, 1, 11, 4, C_AC_NEUTRAL_BORDER)
    draw_minus(cg_top, 8, 2, C_WHITE)
    draw_arrow_up(cg_top, 8, 4, C_IN_BLUE_LED)
    # South terminal: + / L
    cg_top.rect(4, 11, 11, 14, C_DC_PLUS_BG)
    cg_top.frame(4, 11, 11, 14, C_AC_PHASE_BORDER)
    draw_plus(cg_top, 8, 13, C_WHITE)
    draw_arrow_down(cg_top, 8, 11, C_OUT_GREEN_LED)
    # Center: Quantum telemetry display
    cg_top.rect(4, 6, 11, 9, (15, 10, 25, 255))
    cg_top.frame(4, 6, 11, 9, (140, 50, 200, 255))
    cg_top.rect(5, 7, 10, 8, (220, 80, 255, 255))
    cg_top.save(f"{OUT_DIR}/creative_generator_top.png")

    cg_side = Canvas(16, 16, bg=(35, 18, 48, 255))
    cg_side.frame(0, 0, 15, 15, (80, 30, 110, 255))
    cg_side.rect(3, 3, 12, 12, (20, 12, 30, 255))
    cg_side.frame(3, 3, 12, 12, (150, 60, 220, 255))
    cg_side.set(7, 7, (240, 120, 255, 255)); cg_side.set(8, 8, (240, 120, 255, 255))
    cg_side.save(f"{OUT_DIR}/creative_generator_side.png")
    cg_side.save(f"{OUT_DIR}/creative_generator.png")

    cg_bottom = Canvas(16, 16, bg=(20, 12, 28, 255))
    cg_bottom.frame(0, 0, 15, 15, (50, 20, 70, 255))
    cg_bottom.save(f"{OUT_DIR}/creative_generator_bottom.png")

    # Creative Load
    cl_top = Canvas(16, 16, bg=(50, 25, 30, 255))
    cl_top.frame(0, 0, 15, 15, (110, 45, 55, 255))
    cl_top.rect(4, 1, 11, 4, C_DC_MINUS_BG)
    cl_top.frame(4, 1, 11, 4, C_AC_NEUTRAL_BORDER)
    draw_minus(cl_top, 8, 2, C_WHITE)
    draw_arrow_down(cl_top, 8, 4, C_IN_BLUE_LED)
    cl_top.rect(4, 11, 11, 14, C_DC_PLUS_BG)
    cl_top.frame(4, 11, 11, 14, C_AC_PHASE_BORDER)
    draw_plus(cl_top, 8, 13, C_WHITE)
    draw_arrow_up(cl_top, 8, 11, C_OUT_GREEN_LED)
    cl_top.rect(4, 6, 11, 9, (20, 10, 12, 255))
    cl_top.frame(4, 6, 11, 9, (180, 50, 70, 255))
    cl_top.save(f"{OUT_DIR}/creative_load_top.png")

    cl_side = Canvas(16, 16, bg=(40, 22, 26, 255))
    cl_side.frame(0, 0, 15, 15, (90, 35, 45, 255))
    for x in range(2, 14, 2):
        cl_side.rect(x, 2, x, 13, (20, 10, 12, 255))
        cl_side.rect(x+1, 2, x+1, 13, (160, 50, 70, 255))
    cl_side.save(f"{OUT_DIR}/creative_load_side.png")
    cl_side.save(f"{OUT_DIR}/creative_load.png")

    cl_bottom = Canvas(16, 16, bg=(24, 14, 16, 255))
    cl_bottom.frame(0, 0, 15, 15, (60, 25, 30, 255))
    cl_bottom.save(f"{OUT_DIR}/creative_load_bottom.png")

# ==============================================================================
# 6. SWITCHGEAR
# ==============================================================================

def make_switchgear():
    # 1. Circuit Breaker
    cb = Canvas(16, 16, bg=(210, 212, 218, 255))
    cb.frame(0, 0, 15, 15, (150, 154, 162, 255))
    # Top/Front terminal clamp
    cb.rect(5, 1, 10, 3, (40, 42, 46, 255))
    cb.set(7, 2, C_TERM_SCREW); cb.set(8, 2, C_TERM_SCREW)
    # Bottom/Back terminal clamp
    cb.rect(5, 12, 10, 14, (40, 42, 46, 255))
    cb.set(7, 13, C_TERM_SCREW); cb.set(8, 13, C_TERM_SCREW)
    # Center toggle switch bezel
    cb.rect(5, 5, 10, 10, (30, 32, 36, 255))
    cb.frame(5, 5, 10, 10, (70, 72, 78, 255))
    # Red toggle handle in ON position (up)
    cb.rect(6, 6, 9, 8, (220, 35, 35, 255))
    cb.rect(6, 6, 9, 6, (255, 60, 60, 255))
    cb.save(f"{OUT_DIR}/circuit_breaker.png")

    # 2. Fuse Box
    fb = Canvas(16, 16, bg=(225, 222, 215, 255))
    fb.frame(0, 0, 15, 15, (160, 155, 145, 255))
    # Top terminal contact
    fb.rect(5, 1, 10, 3, C_TERM_BRASS)
    fb.set(7, 2, C_TERM_SCREW); fb.set(8, 2, C_TERM_SCREW)
    # Bottom terminal contact
    fb.rect(5, 12, 10, 14, C_TERM_BRASS)
    fb.set(7, 13, C_TERM_SCREW); fb.set(8, 13, C_TERM_SCREW)
    # Cartridge fuse chamber
    fb.rect(4, 4, 11, 11, (30, 32, 34, 255))
    fb.frame(4, 4, 11, 11, (80, 85, 90, 255))
    # Glass fuse with sand/copper filament
    fb.rect(6, 5, 9, 10, (190, 215, 225, 255))
    fb.rect(6, 5, 9, 5, C_TERM_BRASS)
    fb.rect(6, 10, 9, 10, C_TERM_BRASS)
    fb.rect(7, 6, 8, 9, C_TERM_COPPER)
    fb.save(f"{OUT_DIR}/fuse_box.png")

    # 3. Knife Switch
    ks = Canvas(16, 16, bg=(45, 47, 52, 255))
    ks.frame(0, 0, 15, 15, (28, 30, 34, 255))
    # Top jaw terminal contact
    ks.rect(6, 1, 9, 3, C_TERM_COPPER)
    ks.set(7, 2, C_TERM_BRASS); ks.set(8, 2, C_TERM_BRASS)
    # Bottom hinge terminal contact
    ks.rect(6, 12, 9, 14, C_TERM_COPPER)
    ks.set(7, 13, C_TERM_BRASS); ks.set(8, 13, C_TERM_BRASS)
    # Copper knife blade
    ks.rect(7, 4, 8, 11, C_TERM_COPPER)
    ks.set(7, 4, (245, 150, 70, 255))
    # Insulated handle
    ks.rect(6, 7, 9, 8, (18, 18, 20, 255))
    ks.save(f"{OUT_DIR}/knife_switch.png")

    # 4. Copper Busbar
    bb = Canvas(16, 16, bg=(38, 40, 44, 255))
    bb.frame(0, 0, 15, 15, (22, 24, 26, 255))
    # Solid heavy copper bar running top to bottom
    bb.rect(4, 0, 11, 15, C_TERM_COPPER)
    bb.rect(5, 0, 6, 15, (245, 145, 65, 255)) # highlight reflection
    bb.rect(9, 0, 10, 15, (170, 85, 35, 255)) # shadow edge
    # 4 Heavy hex mounting bolts
    for by in [2, 6, 10, 13]:
        bb.rect(7, by, 8, by+1, C_BOLT)
        bb.set(7, by, (215, 220, 230, 255))
        bb.set(8, by+1, (110, 115, 125, 255))
    bb.save(f"{OUT_DIR}/copper_busbar.png")

    # 5. Junction Box
    jb = Canvas(16, 16, bg=(75, 78, 85, 255))
    jb.frame(0, 0, 15, 15, (45, 48, 55, 255))
    # Cable entry grommets on North and South
    jb.rect(6, 0, 9, 1, (25, 27, 30, 255))
    jb.rect(6, 14, 9, 15, (25, 27, 30, 255))
    # Enclosure seal & terminal strip
    jb.rect(3, 3, 12, 12, (32, 34, 38, 255))
    jb.frame(3, 3, 12, 12, (55, 58, 65, 255))
    # Brass terminal block connecting North to South
    jb.rect(6, 4, 9, 11, C_TERM_BRASS)
    for ty in [5, 7, 9, 10]:
        jb.set(7, ty, C_TERM_SCREW)
        jb.set(8, ty, C_TERM_SCREW)
    jb.save(f"{OUT_DIR}/junction_box.png")

    # 6. Contactor Relay
    cr = Canvas(16, 16, bg=(50, 52, 58, 255))
    cr.frame(0, 0, 15, 15, (30, 32, 36, 255))
    # North power terminals
    cr.rect(4, 1, 6, 3, C_TERM_BRASS)
    cr.rect(9, 1, 11, 3, C_TERM_BRASS)
    # South power terminals
    cr.rect(4, 12, 6, 14, C_TERM_BRASS)
    cr.rect(9, 12, 11, 14, C_TERM_BRASS)
    # Solenoid coil in center
    cr.rect(4, 5, 11, 10, C_TERM_COPPER)
    cr.frame(4, 5, 11, 10, (75, 80, 88, 255))
    for y in [6, 8]:
        cr.rect(5, y, 10, y, (140, 70, 25, 255))
    cr.save(f"{OUT_DIR}/contactor_relay.png")

# ==============================================================================
# MAIN EXECUTION
# ==============================================================================

if __name__ == "__main__":
    print("Generating VoltCraft electrical block textures...")

    # 1. DC-DC Converters
    generate_converter("converter_dc_buck", "DC", "DC", "BUCK", (60, 210, 240, 255))
    generate_converter("converter_dc_boost", "DC", "DC", "BOOST", (240, 180, 50, 255))
    generate_converter("converter_dc_buck_boost", "DC", "DC", "BKBS", (200, 80, 240, 255))
    generate_converter("regulator_linear_ldo", "DC", "DC", "LDO", (80, 220, 120, 255))
    generate_converter("charge_controller_mppt", "DC", "DC", "MPPT", (50, 230, 180, 255))

    # 2. Inverters (DC in -> AC out)
    generate_converter("inverter_square_wave", "DC", "AC", "SQWV", (220, 80, 40, 255))
    generate_converter("inverter_modified_sine", "DC", "AC", "MSIN", (230, 140, 40, 255))
    generate_converter("inverter_pure_sine", "DC", "AC", "PSIN", (70, 160, 255, 255))
    generate_converter("inverter_grid_tie", "DC", "AC", "GRID", (50, 230, 120, 255))
    generate_converter("inverter_hybrid_ess", "DC", "AC", "HYBR", (240, 210, 50, 255))

    # 3. Rectifiers (AC in -> DC out)
    generate_converter("rectifier_bridge", "AC", "DC", "BRID", (220, 100, 40, 255))
    generate_converter("rectifier_active_synchronous", "AC", "DC", "SYNC", (60, 220, 200, 255))

    # 4. Transformers (AC in -> AC out)
    generate_converter("transformer_ac_step_down", "AC", "AC", "STPD", (230, 130, 40, 255))
    generate_converter("transformer_ac_step_up", "AC", "AC", "STPU", (240, 80, 40, 255))

    # 5. EU Converter (230V AC in -> EU/FE storage out)
    generate_converter("converter_eu", "AC", "EU", "EU", (230, 60, 60, 255))

    # 6. Solar Panels
    make_solar_panels()

    # 7. Batteries
    make_battery_block("battery_block_lifepo4", (28, 55, 115, 255), "LFP")
    make_battery_block("battery_block_lead_acid", (36, 38, 42, 255), "Pb")
    make_battery_block("battery_block_lto", (175, 182, 192, 255), "LTO")
    make_battery_block("battery_block_nimh", (45, 95, 55, 255), "NiMH")
    make_battery_block("battery_block_nicd", (175, 95, 40, 255), "NiCd")
    make_battery_block("battery_rack_modular", (22, 25, 30, 255), "RACK", is_rack=True)

    # 8. Generators
    make_generator_hand_crank()
    make_generator_portable_inverter()

    # 9. Creative Hardware
    make_creative_blocks()

    # 10. Switchgear
    make_switchgear()

    print("All VoltCraft textures generated successfully!")

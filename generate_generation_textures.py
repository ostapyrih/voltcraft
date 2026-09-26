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
        for y in range(y1, y2 + 1):
            for x in range(x1, x2 + 1):
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

# Standard palettes
C_CHASSIS_DARK  = (32, 33, 36, 255)
C_CHASSIS_MID   = (48, 50, 55, 255)
C_CHASSIS_LIGHT = (72, 75, 82, 255)
C_BOLT          = (130, 135, 145, 255)

# Output Green Palette
C_OUT_BORDER    = (28, 140, 56, 255)
C_OUT_LIGHT     = (60, 205, 95, 255)
C_OUT_TEXT      = (220, 255, 200, 255)
C_OUT_LED       = (70, 255, 100, 255)

# Input Blue Palette
C_IN_BORDER     = (30, 95, 185, 255)
C_IN_LIGHT      = (65, 145, 245, 255)
C_IN_TEXT       = (200, 240, 255, 255)
C_IN_LED        = (0, 210, 255, 255)

# Terminals
C_TERM_RED      = (215, 40, 40, 255)
C_TERM_BLACK    = (20, 20, 24, 255)
C_TERM_SCREW    = (200, 205, 215, 255)

# Aluminum Frame
C_ALUM_LIGHT    = (195, 200, 208, 255)
C_ALUM_MID      = (160, 166, 175, 255)
C_ALUM_DARK     = (115, 120, 130, 255)

# 1. Monocrystalline Solar Panel
def make_solar_mono():
    # Top: Dark black/charcoal cells with silver busbars
    top = Canvas(16, 16, bg=(24, 26, 32, 255))
    top.frame(0, 0, 15, 15, C_ALUM_MID)
    # 4 distinct cell quadrants with subtle anti-reflective blue-black sheen
    for cx, cy in [(1, 1), (8, 1), (1, 8), (8, 8)]:
        top.rect(cx, cy, cx+6, cy+6, (20, 23, 30, 255))
        # Octagonal cell corners
        top.set(cx, cy, (35, 38, 45, 255))
        top.set(cx+6, cy, (35, 38, 45, 255))
        top.set(cx, cy+6, (35, 38, 45, 255))
        top.set(cx+6, cy+6, (35, 38, 45, 255))
    # Silver busbars crossing cells
    top.rect(4, 1, 4, 14, (190, 195, 205, 255))
    top.rect(11, 1, 11, 14, (190, 195, 205, 255))
    top.rect(1, 4, 14, 4, (160, 165, 175, 255))
    top.rect(1, 11, 14, 11, (160, 165, 175, 255))
    top.save(f"{OUT_DIR}/solar_panel_monocrystalline_top.png")

    # Bottom: Tedlar backing with center junction box
    bottom = Canvas(16, 16, bg=(35, 37, 40, 255))
    bottom.frame(0, 0, 15, 15, C_ALUM_DARK)
    bottom.rect(5, 5, 10, 10, (18, 19, 22, 255))
    bottom.frame(5, 5, 10, 10, (55, 58, 65, 255))
    bottom.set(6, 7, C_TERM_RED); bottom.set(6, 8, C_TERM_RED)
    bottom.set(9, 7, C_TERM_BLACK); bottom.set(9, 8, C_TERM_BLACK)
    bottom.save(f"{OUT_DIR}/solar_panel_monocrystalline_bottom.png")

    # Side: Extruded aluminum profile
    side = Canvas(16, 16, bg=C_ALUM_MID)
    side.rect(0, 0, 15, 1, C_ALUM_LIGHT)
    side.rect(0, 5, 15, 7, C_ALUM_DARK) # mounting groove
    side.rect(0, 14, 15, 15, (95, 100, 108, 255))
    side.save(f"{OUT_DIR}/solar_panel_monocrystalline_side.png")

# 2. Polycrystalline Solar Panel
def make_solar_poly():
    # Top: Royal blue crystalline sparkle
    top = Canvas(16, 16, bg=(25, 60, 130, 255))
    top.frame(0, 0, 15, 15, C_ALUM_MID)
    # Crystal grain texture
    flakes = [
        (2, 2, 4, 4, (35, 80, 170, 255)),
        (5, 2, 7, 5, (20, 50, 115, 255)),
        (2, 5, 4, 7, (45, 95, 190, 255)),
        (8, 2, 11, 4, (30, 70, 150, 255)),
        (12, 2, 14, 5, (40, 85, 180, 255)),
        (8, 5, 11, 7, (20, 55, 120, 255)),
        (2, 8, 5, 11, (35, 75, 165, 255)),
        (6, 8, 7, 10, (50, 105, 205, 255)),
        (2, 12, 7, 14, (25, 60, 135, 255)),
        (8, 8, 11, 10, (40, 90, 185, 255)),
        (12, 8, 14, 11, (20, 50, 115, 255)),
        (8, 11, 14, 14, (35, 80, 170, 255))
    ]
    for x1, y1, x2, y2, col in flakes:
        top.rect(x1, y1, x2, y2, col)
    # Silver busbars
    top.rect(4, 1, 4, 14, (205, 210, 220, 255))
    top.rect(11, 1, 11, 14, (205, 210, 220, 255))
    top.save(f"{OUT_DIR}/solar_panel_polycrystalline_top.png")

    # Bottom: White Tedlar backing
    bottom = Canvas(16, 16, bg=(220, 225, 230, 255))
    bottom.frame(0, 0, 15, 15, C_ALUM_DARK)
    bottom.rect(5, 5, 10, 10, (25, 28, 32, 255))
    bottom.set(6, 7, C_TERM_RED); bottom.set(9, 7, C_TERM_BLACK)
    bottom.save(f"{OUT_DIR}/solar_panel_polycrystalline_bottom.png")

    # Side
    side = Canvas(16, 16, bg=C_ALUM_MID)
    side.rect(0, 0, 15, 1, C_ALUM_LIGHT)
    side.rect(0, 5, 15, 7, C_ALUM_DARK)
    side.save(f"{OUT_DIR}/solar_panel_polycrystalline_side.png")

# 3. Thin-Film CdTe Solar Panel
def make_solar_thin_film():
    # Top: Dark reddish-brown / obsidian glass
    top = Canvas(16, 16, bg=(28, 22, 26, 255))
    top.frame(0, 0, 15, 15, (45, 40, 44, 255))
    # Scribed laser lines characteristic of thin film
    for x in [3, 6, 9, 12]:
        top.rect(x, 1, x, 14, (40, 32, 36, 255))
    top.save(f"{OUT_DIR}/solar_panel_thin_film_top.png")

    # Bottom: Dark composite backing
    bottom = Canvas(16, 16, bg=(20, 20, 22, 255))
    bottom.rect(6, 6, 9, 9, (12, 12, 15, 255))
    bottom.save(f"{OUT_DIR}/solar_panel_thin_film_bottom.png")

    # Side: Frameless tempered glass edge
    side = Canvas(16, 16, bg=(38, 42, 45, 255))
    side.rect(0, 0, 15, 2, (70, 75, 80, 255))
    side.rect(0, 14, 15, 15, (25, 27, 30, 255))
    side.save(f"{OUT_DIR}/solar_panel_thin_film_side.png")

# 4. Concentrator CPV Solar Panel
def make_solar_concentrator():
    # Top: Gold/amber matrix of micro-Fresnel lenses
    top = Canvas(16, 16, bg=(210, 155, 30, 255))
    top.frame(0, 0, 15, 15, C_ALUM_LIGHT)
    # Concentric Fresnel lens squares
    for cx, cy in [(2, 2), (9, 2), (2, 9), (9, 9)]:
        top.rect(cx, cy, cx+4, cy+4, (245, 195, 60, 255))
        top.frame(cx, cy, cx+4, cy+4, (185, 130, 20, 255))
        top.set(cx+2, cy+2, (255, 240, 150, 255)) # optical focus hot spot
    top.save(f"{OUT_DIR}/solar_panel_concentrator_top.png")

    # Bottom: Deep heatsink cooling fins
    bottom = Canvas(16, 16, bg=(60, 64, 70, 255))
    for y in range(1, 15, 2):
        bottom.rect(1, y, 14, y, (35, 38, 42, 255))
    bottom.save(f"{OUT_DIR}/solar_panel_concentrator_bottom.png")

    # Side: Deep aluminum ribbed heatsink
    side = Canvas(16, 16, bg=(55, 58, 64, 255))
    for y in range(2, 14, 3):
        side.rect(0, y, 15, y, (30, 32, 36, 255))
        side.rect(0, y+1, 15, y+1, (80, 85, 95, 255))
    side.save(f"{OUT_DIR}/solar_panel_concentrator_side.png")

# 5. MPPT Solar Charge Controller
def make_mppt():
    # Front: Green OUT to battery, MPPT LCD display
    front = Canvas(16, 16, bg=C_CHASSIS_DARK)
    front.rect(1, 1, 14, 14, C_CHASSIS_MID)
    # 4 corner bolts
    for bx, by in [(1, 1), (14, 1), (1, 14), (14, 14)]:
        front.set(bx, by, C_BOLT)
    # Green OUT header banner
    front.rect(2, 1, 13, 2, C_OUT_BORDER)
    front.rect(7, 1, 8, 2, C_OUT_LED)
    # LCD Display screen with cyan "MPPT" telemetry
    front.rect(2, 4, 13, 8, (15, 28, 30, 255))
    front.frame(2, 4, 13, 8, (30, 55, 60, 255))
    # Font "OUT" in green on display
    front.set(3, 5, C_OUT_TEXT); front.set(4, 5, C_OUT_TEXT); front.set(5, 5, C_OUT_TEXT)
    front.set(3, 6, C_OUT_TEXT); front.set(5, 6, C_OUT_TEXT)
    front.set(3, 7, C_OUT_TEXT); front.set(4, 7, C_OUT_TEXT); front.set(5, 7, C_OUT_TEXT)

    front.set(7, 5, C_OUT_TEXT); front.set(9, 5, C_OUT_TEXT)
    front.set(7, 6, C_OUT_TEXT); front.set(9, 6, C_OUT_TEXT)
    front.set(7, 7, C_OUT_TEXT); front.set(8, 7, C_OUT_TEXT); front.set(9, 7, C_OUT_TEXT)

    front.set(11, 5, C_OUT_TEXT); front.set(12, 5, C_OUT_TEXT); front.set(13, 5, C_OUT_TEXT)
    front.set(12, 6, C_OUT_TEXT); front.set(12, 7, C_OUT_TEXT)

    # Output screw terminals at bottom
    front.rect(3, 10, 6, 13, (20, 22, 25, 255))
    front.rect(9, 10, 12, 13, (20, 22, 25, 255))
    front.frame(3, 10, 6, 13, C_OUT_BORDER)
    front.frame(9, 10, 12, 13, C_OUT_BORDER)
    front.set(4, 11, C_TERM_RED); front.set(5, 11, C_TERM_SCREW)
    front.set(10, 11, C_TERM_BLACK); front.set(11, 11, C_TERM_SCREW)
    front.save(f"{OUT_DIR}/charge_controller_mppt_front.png")

    # Back: Blue IN from PV String
    back = Canvas(16, 16, bg=C_CHASSIS_DARK)
    back.rect(1, 1, 14, 14, C_CHASSIS_MID)
    for bx, by in [(1, 1), (14, 1), (1, 14), (14, 14)]:
        back.set(bx, by, C_BOLT)
    # Blue IN banner
    back.rect(2, 1, 13, 2, C_IN_BORDER)
    back.rect(7, 1, 8, 2, C_IN_LED)
    # Font "IN"
    back.rect(4, 4, 11, 8, (15, 22, 35, 255))
    back.frame(4, 4, 11, 8, (30, 45, 75, 255))
    # I (x=5..6)
    back.set(5, 5, C_IN_TEXT); back.set(6, 5, C_IN_TEXT)
    back.set(5, 6, C_IN_TEXT); back.set(6, 6, C_IN_TEXT)
    back.set(5, 7, C_IN_TEXT); back.set(6, 7, C_IN_TEXT)
    # N (x=8..10)
    back.set(8, 5, C_IN_TEXT); back.set(10, 5, C_IN_TEXT)
    back.set(8, 6, C_IN_TEXT); back.set(9, 6, C_IN_TEXT); back.set(10, 6, C_IN_TEXT)
    back.set(8, 7, C_IN_TEXT); back.set(10, 7, C_IN_TEXT)

    # PV input terminals at bottom
    back.rect(3, 10, 6, 13, (20, 22, 25, 255))
    back.rect(9, 10, 12, 13, (20, 22, 25, 255))
    back.frame(3, 10, 6, 13, C_IN_BORDER)
    back.frame(9, 10, 12, 13, C_IN_BORDER)
    back.set(4, 11, C_TERM_RED); back.set(5, 11, C_TERM_SCREW)
    back.set(10, 11, C_TERM_BLACK); back.set(11, 11, C_TERM_SCREW)
    back.save(f"{OUT_DIR}/charge_controller_mppt_back.png")

    # Side: Black heatsink fins
    side = Canvas(16, 16, bg=(28, 30, 34, 255))
    for x in range(2, 14, 2):
        side.rect(x, 1, x, 14, (15, 16, 18, 255))
        side.rect(x+1, 1, x+1, 14, (50, 53, 60, 255))
    side.save(f"{OUT_DIR}/charge_controller_mppt_side.png")

    # Top: Ventilation grille & warning label
    top = Canvas(16, 16, bg=C_CHASSIS_MID)
    top.frame(0, 0, 15, 15, C_CHASSIS_DARK)
    for y in [3, 5, 7, 9, 11]:
        top.rect(3, y, 12, y, (20, 21, 24, 255))
    top.save(f"{OUT_DIR}/charge_controller_mppt_top.png")

    # Bottom: Metal mounting base
    bottom = Canvas(16, 16, bg=C_CHASSIS_DARK)
    bottom.rect(3, 3, 12, 12, (38, 40, 44, 255))
    bottom.save(f"{OUT_DIR}/charge_controller_mppt_bottom.png")

# 6. Hand Crank Generator
def make_hand_crank():
    # Front: Green OUT terminals with analog circular voltmeter
    front = Canvas(16, 16, bg=(50, 52, 56, 255))
    front.frame(0, 0, 15, 15, (30, 32, 34, 255))
    # Circular analog dial
    front.rect(4, 2, 11, 8, (230, 232, 235, 255))
    front.frame(4, 2, 11, 8, (70, 72, 75, 255))
    front.rect(5, 7, 10, 7, (200, 30, 30, 255)) # red gauge scale
    front.set(8, 5, (20, 20, 20, 255)) # needle pivot
    front.set(7, 4, (20, 20, 20, 255)) # needle
    # Green OUT banner & terminals
    front.rect(3, 10, 12, 14, C_OUT_BORDER)
    front.set(5, 12, C_TERM_RED); front.set(6, 12, C_TERM_SCREW)
    front.set(9, 12, C_TERM_BLACK); front.set(10, 12, C_TERM_SCREW)
    front.save(f"{OUT_DIR}/generator_hand_crank_front.png")

    # Back: Stator casing & copper windings
    back = Canvas(16, 16, bg=(42, 44, 48, 255))
    back.frame(0, 0, 15, 15, (28, 30, 32, 255))
    back.rect(4, 4, 11, 11, (180, 95, 40, 255)) # Copper coils
    back.frame(4, 4, 11, 11, (85, 88, 92, 255))
    for y in [5, 7, 9]:
        back.rect(5, y, 10, y, (140, 70, 25, 255))
    back.save(f"{OUT_DIR}/generator_hand_crank_back.png")

    # Top: Crank hub & brass drive gears
    top = Canvas(16, 16, bg=(55, 57, 62, 255))
    top.frame(0, 0, 15, 15, (35, 37, 40, 255))
    top.rect(5, 5, 10, 10, (185, 150, 45, 255)) # Brass gear casing
    top.frame(5, 5, 10, 10, (135, 105, 30, 255))
    top.rect(7, 7, 8, 8, (215, 220, 225, 255)) # Steel spindle shaft
    top.save(f"{OUT_DIR}/generator_hand_crank_top.png")

    # Bottom: Pedestal base with rubber feet
    bottom = Canvas(16, 16, bg=(35, 36, 38, 255))
    for bx, by in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        bottom.rect(bx-1, by-1, bx, by, (15, 15, 16, 255))
    bottom.save(f"{OUT_DIR}/generator_hand_crank_bottom.png")

    # Side: Cast iron casing
    side = Canvas(16, 16, bg=(48, 50, 54, 255))
    side.frame(0, 0, 15, 15, (30, 32, 34, 255))
    side.rect(3, 4, 12, 11, (36, 38, 42, 255))
    side.save(f"{OUT_DIR}/generator_hand_crank_side.png")

# 7. Portable Inverter Generator
def make_portable_gen():
    # Industrial Red Body
    C_GEN_RED = (185, 35, 30, 255)
    C_GEN_RED_DARK = (135, 22, 18, 255)

    # Front: 230V AC socket [OUT], Eco switch, breaker
    front = Canvas(16, 16, bg=C_GEN_RED)
    front.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    # Control panel bezel
    front.rect(2, 2, 13, 13, (28, 30, 34, 255))
    front.frame(2, 2, 13, 13, (55, 58, 64, 255))
    # Green OUT AC Socket (Schuko style)
    front.rect(3, 3, 9, 8, C_OUT_BORDER)
    front.rect(4, 4, 8, 7, (18, 20, 22, 255))
    front.set(5, 5, (230, 210, 60, 255)) # Brass pin hole
    front.set(7, 5, (230, 210, 60, 255)) # Brass pin hole
    front.set(6, 4, (180, 185, 195, 255)) # Ground clip
    front.set(6, 7, (180, 185, 195, 255)) # Ground clip
    # Eco throttle rocker switch
    front.rect(11, 4, 12, 7, (60, 62, 68, 255))
    front.set(11, 4, (70, 220, 90, 255)) # Green eco led
    # Status LEDs: Green Run, Red Overload, Amber Low Oil
    front.set(4, 11, (60, 240, 80, 255))
    front.set(6, 11, (240, 40, 40, 255))
    front.set(8, 11, (240, 180, 20, 255))
    # Breaker button
    front.rect(11, 10, 12, 12, (200, 30, 30, 255))
    front.save(f"{OUT_DIR}/generator_portable_inverter_front.png")

    # Back: Recoil starter pull handle & rope
    back = Canvas(16, 16, bg=C_GEN_RED)
    back.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    back.rect(4, 4, 11, 11, (32, 34, 38, 255))
    back.frame(4, 4, 11, 11, (65, 68, 75, 255))
    # T-handle pull cord
    back.rect(6, 3, 9, 4, (15, 15, 15, 255)) # Handle grip
    back.rect(7, 5, 8, 8, (215, 218, 225, 255)) # Starter cord
    back.save(f"{OUT_DIR}/generator_portable_inverter_back.png")

    # Top: Carry handle & fuel tank cap
    top = Canvas(16, 16, bg=C_GEN_RED)
    top.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    # Heavy central carry handle
    top.rect(7, 1, 8, 14, (30, 32, 36, 255))
    top.frame(7, 1, 8, 14, (60, 64, 70, 255))
    # Fuel tank cap
    top.rect(2, 5, 5, 8, (20, 20, 22, 255))
    top.frame(2, 5, 5, 8, (50, 52, 58, 255))
    top.set(3, 6, (200, 160, 30, 255)) # Fuel icon
    top.save(f"{OUT_DIR}/generator_portable_inverter_top.png")

    # Bottom: Anti-vibration rubber isolation mounts
    bottom = Canvas(16, 16, bg=(25, 26, 28, 255))
    for bx, by in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        bottom.rect(bx-1, by-1, bx, by, (12, 12, 14, 255))
    bottom.rect(7, 7, 8, 8, (140, 145, 155, 255)) # Oil drain plug
    bottom.save(f"{OUT_DIR}/generator_portable_inverter_bottom.png")

    # Side: Exhaust muffler & engine ventilation
    side = Canvas(16, 16, bg=C_GEN_RED)
    side.frame(0, 0, 15, 15, C_GEN_RED_DARK)
    # Muffler heat shield with perforations
    side.rect(8, 3, 14, 9, (45, 48, 52, 255))
    side.frame(8, 3, 14, 9, (80, 85, 92, 255))
    # Exhaust outlet pipe
    side.rect(10, 5, 12, 7, (20, 22, 24, 255))
    side.set(11, 6, (10, 10, 12, 255))
    # Engine intake louvered slats
    for y in [4, 6, 8, 10, 12]:
        side.rect(2, y, 6, y, (25, 27, 30, 255))
    side.save(f"{OUT_DIR}/generator_portable_inverter_side.png")

if __name__ == "__main__":
    make_solar_mono()
    make_solar_poly()
    make_solar_thin_film()
    make_solar_concentrator()
    make_mppt()
    make_hand_crank()
    make_portable_gen()
    print("Generation textures generated successfully!")

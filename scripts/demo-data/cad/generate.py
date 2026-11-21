"""Generates the demo data's CAD models (STEP files) with CadQuery.

The models are simplified but to scale (millimetres): enough to look at, measure and section in the
viewer. They're committed next to this script, so loading the demo needs none of this; to change
or regenerate them:

    python3 -m venv .venv && .venv/bin/pip install cadquery
    .venv/bin/python scripts/demo-data/cad/generate.py
"""

import math
import pathlib

import cadquery as cq

OUT = pathlib.Path(__file__).parent
ALU = cq.Color(0.75, 0.77, 0.80)
DARK = cq.Color(0.22, 0.23, 0.25)
CELL = cq.Color(0.20, 0.38, 0.62)
COPPER = cq.Color(0.80, 0.48, 0.25)
ORANGE = cq.Color(0.95, 0.45, 0.10)
GREEN = cq.Color(0.15, 0.55, 0.35)
BODY = cq.Color(0.82, 0.84, 0.88)
GLASS = cq.Color(0.15, 0.20, 0.28)
RUBBER = cq.Color(0.10, 0.10, 0.11)


def save(shape, name):
    path = OUT / f"{name}.stp"
    if isinstance(shape, cq.Assembly):
        shape.save(str(path), exportType="STEP")
    else:
        cq.exporters.export(shape, str(path))
    print(f"{path.name}: {path.stat().st_size // 1024} KB")


# ---- the long LFP cell: 960 x 90 x 13.5 mm, terminals at both ends, a vent ----
def cell():
    body = cq.Workplane("XY").box(960, 13.5, 90).edges("|Y").fillet(2)
    for x in (-470, 470):
        body = body.union(cq.Workplane("XY").box(12, 9, 6).translate((x, 0, 48)))
    body = body.cut(cq.Workplane("XY").circle(4).extrude(3).translate((0, 0, 43.5)))
    return body


def cell_assembly():
    a = cq.Assembly(name="Long LFP cell 138 Ah")
    a.add(cell(), name="cell", color=CELL)
    return a


# ---- the battery pack: tray, 137 cells, cooling plates, junction box, BMS, lid ----
def pack():
    a = cq.Assembly(name="Battery pack, cell-to-body")
    length, width = 1960, 1080
    tray = cq.Workplane("XY").box(length, width, 110).translate((0, 0, 55))
    tray = tray.cut(cq.Workplane("XY").box(length - 40, width - 40, 110).translate((0, 0, 70)))
    for y in (-width / 2 - 25, width / 2 + 25):  # side sills with mounting holes
        sill = cq.Workplane("XY").box(length, 50, 60).translate((0, y, 80))
        for x in range(-900, 901, 150):
            sill = sill.cut(cq.Workplane("XY").circle(6).extrude(80).translate((x, y, 40)))
        tray = tray.union(sill)
    # The lid first in the model tree: hide it to see the cells.
    lid = cq.Workplane("XY").box(length, width, 3).translate((0, 0, 125)).edges("|Z").fillet(20)
    a.add(lid, name="Pack lid (cabin floor)", color=cq.Color(0.55, 0.57, 0.60, 0.85))
    a.add(tray, name="Pack tray (aluminium extrusion)", color=ALU)
    for i, x in enumerate((-480, 480)):
        a.add(cq.Workplane("XY").box(880, width - 60, 6).translate((x, 0, 23)), name=f"Cooling plate {i + 1}", color=COPPER)
    c = cell().rotate((0, 0, 0), (0, 0, 1), 90)
    pitch = 13.9
    n = 137
    for i in range(n):
        x = -(n - 1) * pitch / 2 + i * pitch
        a.add(c, name="Long cell" if i == 0 else f"Long cell {i + 1}", loc=cq.Location((x, 0, 26 + 45)), color=CELL)
    a.add(cq.Workplane("XY").box(140, 260, 70).edges("|Z").fillet(8).translate((-length / 2 + 110, 380, 150)), name="HV junction box", color=ORANGE)
    a.add(cq.Workplane("XY").box(180, 120, 30).translate((-length / 2 + 110, -380, 130)), name="Battery management controller", color=GREEN)
    return a


# ---- the 8-in-1 electric drive unit: motor, reducer, inverter, output shafts ----
def drive_unit():
    a = cq.Assembly(name="8-in-1 electric drive unit")
    motor = cq.Workplane("YZ").circle(115).extrude(300).translate((-300, 0, 0))
    for i in range(18):  # cooling fins
        ang = i * 20
        fin = cq.Workplane("XY").box(280, 6, 18).translate((-150, 0, 120)).rotate((0, 0, 0), (1, 0, 0), ang)
        motor = motor.union(fin)
    a.add(motor, name="Traction motor PMSM", color=ALU)
    reducer = cq.Workplane("XY").box(170, 260, 330).edges("|X").fillet(40).translate((85, -40, -10))
    a.add(reducer, name="Single-speed reducer", color=cq.Color(0.62, 0.64, 0.66))
    inv = cq.Workplane("XY").box(320, 240, 90).edges("|Z").fillet(10).translate((-140, 0, 165))
    a.add(inv, name="SiC inverter and controllers (8-in-1)", color=DARK)
    for x, name in ((-470, "Output shaft, left"), (330, "Output shaft, right")):
        a.add(cq.Workplane("YZ").circle(22).extrude(160).translate((x if x > 0 else x, -80, -60)), name=name, color=cq.Color(0.45, 0.45, 0.47))
    for i, (x, y) in enumerate(((-260, 130), (-260, -130), (120, 140))):
        a.add(cq.Workplane("XY").box(60, 40, 120).translate((x, y, -150)), name=f"Mount bracket {i + 1}", color=DARK)
    for i, x in enumerate((-60, -10)):
        a.add(cq.Workplane("XY").circle(14).extrude(40).translate((x, 120, 205)), name=f"HV connector {i + 1}", color=ORANGE)
    return a


# ---- a 19-inch forged wheel: rim, five twin spokes, hub ----
def wheel_shape():
    r_rim = 241  # 19 in bead seat radius
    width = 216  # 8.5 in
    profile = (
        cq.Workplane("XZ")
        .polyline([(r_rim - 12, -width / 2), (r_rim + 12, -width / 2), (r_rim + 12, -width / 2 + 14), (r_rim, -width / 2 + 20),
                   (r_rim - 30, -10), (r_rim - 30, 20), (r_rim, width / 2 - 20), (r_rim + 12, width / 2 - 14),
                   (r_rim + 12, width / 2), (r_rim - 12, width / 2), (r_rim - 12, width / 2 - 14),
                   (r_rim - 42, 20), (r_rim - 42, -10), (r_rim - 12, -width / 2 + 14)])
        .close()
        .revolve(360, (0, 0, 0), (0, 1, 0))
    )
    wheel = profile.rotate((0, 0, 0), (1, 0, 0), 90)
    hub = cq.Workplane("XY").circle(80).extrude(40).translate((0, 0, 50))
    hub = hub.cut(cq.Workplane("XY").circle(33).extrude(60).translate((0, 0, 40)))
    for i in range(5):
        a = math.radians(i * 72)
        hub = hub.cut(cq.Workplane("XY").circle(8).extrude(60).translate((57 * math.cos(a), 57 * math.sin(a), 40)))
    wheel = wheel.union(hub)
    for i in range(5):
        for off in (-8, 8):
            spoke = cq.Workplane("XY").box(160, 26, 40).translate((150, 0, 68)).rotate((0, 0, 0), (0, 0, 1), i * 72 + off)
            wheel = wheel.union(spoke)
    return wheel


def wheel():
    a = cq.Assembly(name="Wheel 19 in, forged")
    a.add(wheel_shape(), name="Wheel", color=ALU)
    return a


# ---- a steering knuckle: arms, bearing boss, caliper mounts ----
def knuckle():
    boss = cq.Workplane("XY").circle(70).extrude(70).cut(cq.Workplane("XY").circle(42).extrude(70))
    upper = cq.Workplane("XZ").polyline([(-25, 60), (25, 60), (15, 260), (-15, 260)]).close().extrude(30).translate((0, 15, 0))
    lower = cq.Workplane("XZ").polyline([(-30, -60), (30, -60), (20, -190), (-20, -190)]).close().extrude(34).translate((0, 17, 0))
    steer = cq.Workplane("XY").box(170, 26, 30).translate((110, 0, -40))
    k = boss.union(upper.translate((0, 0, 35))).union(lower.translate((0, 0, 35))).union(steer.translate((0, 0, 35)))
    for z in (295, -155):
        k = k.union(cq.Workplane("XY").circle(24).extrude(36).translate((0, -18, z)).rotate((0, 0, 0), (0, 0, 1), 0))
    for z in (120, -50):  # caliper mounting ears
        k = k.union(cq.Workplane("XY").box(40, 50, 26).translate((-90, 0, z)))
        k = k.cut(cq.Workplane("XY").circle(7).extrude(60).translate((-90, 0, z - 30)))
    return k


def knuckle_assembly():
    a = cq.Assembly(name="Steering knuckle, front")
    a.add(knuckle(), name="Knuckle", color=cq.Color(0.60, 0.62, 0.65))
    return a


# ---- an M12 x 65 flange bolt ----
def bolt():
    head = cq.Workplane("XY").polygon(6, 20.8).extrude(10).edges(">Z").chamfer(1)
    flange = cq.Workplane("XY").circle(13).extrude(3).translate((0, 0, -3))
    shank = cq.Workplane("XY").circle(6).extrude(65).translate((0, 0, -68))
    b = head.union(flange).union(shank)
    for i in range(20):  # thread crests (rings, for the look)
        b = b.union(cq.Workplane("XY").circle(6.0).circle(5.4).extrude(0.8).translate((0, 0, -68 + 2 + i * 1.75)))
    return b


def bolt_assembly():
    a = cq.Assembly(name="Flange bolt M12x65")
    a.add(bolt(), name="Bolt", color=cq.Color(0.35, 0.36, 0.38))
    return a


# ---- the vehicle packaging model: body, glasshouse, pack, drive units, wheels ----
def vehicle():
    a = cq.Assembly(name="Sedan - packaging model")
    L, W, wb, track = 4980, 1910, 2920, 1620
    # Lower body, sill to beltline, rounded in plan and at the shoulder.
    lower = cq.Workplane("XY").box(L, W, 640).translate((0, 0, 330 + 320))
    lower = lower.edges("|Z").fillet(420).edges(">Z").fillet(140).edges("<Z").fillet(60)
    for x in (-wb / 2, wb / 2):  # wheel arches
        lower = lower.cut(cq.Workplane("XZ").circle(395).extrude(W, both=True).translate((x, 0, 360)))
    a.add(lower, name="Body (simplified)", color=BODY)
    # Glasshouse: a loft from the beltline to the roof, set back and narrower.
    belt = cq.Workplane("XY").workplane(offset=960).center(150, 0).rect(2900, 1700)
    cabin = belt.workplane(offset=500).center(120, 0).rect(1500, 1300).loft(combine=True)
    a.add(cabin, name="Glasshouse", color=GLASS)
    roof = cq.Workplane("XY").box(1420, 1220, 18).edges("|Z").fillet(180).translate((270, 0, 1469))
    a.add(roof, name="Roof panel", color=BODY)
    a.add(cq.Workplane("XY").box(1960, 1080, 120).translate((0, 0, 260)), name="Battery pack (in the floor)", color=CELL)
    a.add(cq.Workplane("XY").box(600, 500, 400).translate((wb / 2, 0, 400)), name="Rear drive unit", color=DARK)
    a.add(cq.Workplane("XY").box(520, 460, 380).translate((-wb / 2, 0, 410)), name="Front drive unit (AWD)", color=DARK)
    w = wheel_shape()
    tyre = cq.Workplane("XY").circle(360).circle(250).extrude(235).translate((0, 0, -10)).edges().fillet(30)
    for x, side, name in ((-wb / 2, -1, "front left"), (-wb / 2, 1, "front right"), (wb / 2, -1, "rear left"), (wb / 2, 1, "rear right")):
        rot = 90 if side < 0 else -90
        loc = cq.Location((x, side * (track / 2 - 10), 360), (1, 0, 0), rot)
        # A copy each, so every wheel and tyre is its own model with its own name.
        a.add(w.translate((0, 0, 0)), name=f"Wheel, {name}", loc=loc, color=ALU)
        a.add(tyre.translate((0, 0, 0)), name=f"Tyre, {name}", loc=loc, color=RUBBER)
    return a


if __name__ == "__main__":
    save(cell_assembly(), "VX3-CL-138")
    save(pack(), "VX3-BP-60")
    save(drive_unit(), "VX3-ED-150")
    save(wheel(), "AUR-WH-19")
    save(knuckle_assembly(), "VX3-CH-101")
    save(bolt_assembly(), "STD-F-1265")
    save(vehicle(), "AUR-0001")

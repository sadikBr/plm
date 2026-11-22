// Demo data set "byd": the same plant and data shape as "voltara", with BYD's real vehicle and
// technology names (Seal, Dolphin, Seal U DM-i, e-Platform 3.0, Blade Battery, 8-in-1 powertrain,
// DM-i) and their public headline figures (battery sizes, motor power, range, dimensions,
// approximate). Everything inside is invented for testing: part numbers, structures, documents,
// test results, problems and changes. It is not BYD's data.
import * as V from "./voltara.mjs";

export const COMPANY = "BYD";

// Applied in order to every text of the voltara set.
const RENAME = [
  // part and document numbers
  ["VX3-", "EP3-"],
  ["AUR-", "SEAL-"],
  ["TRN-", "DOL-"],
  ["RDG-", "SLU-"],
  ["STD-", "BSTD-"],
  ["DOC-", "BDOC-"],
  ["WI-", "BWI-"],
  ["PFMEA-", "BPFMEA-"],
  ["CP-BIW", "BCP-BIW"],
  ["QP-EOL", "BQP-EOL"],
  ["LAY-L3", "BLAY-L3"],
  // products and technology
  ["VX-3 EV Platform", "e-Platform 3.0"],
  ["VX-3", "e-Platform 3.0"],
  ["Aurora Sedan", "BYD Seal"],
  ["Aurora sedan", "BYD Seal"],
  ["Aurora", "Seal"],
  ["Tern Hatchback", "BYD Dolphin"],
  ["Tern hatchback", "BYD Dolphin"],
  ["Tern", "Dolphin"],
  ["Ridge SUV (PHEV)", "BYD Seal U DM-i"],
  ["Ridge SUV PHEV", "BYD Seal U DM-i"],
  ["Ridge", "Seal U DM-i"],
  ["LFP long cell", "Blade Battery cell"],
  ["long LFP cells", "Blade Battery cells"],
  ["Long prismatic LFP cell", "Blade Battery cell (long prismatic LFP)"],
  ["long cells", "Blade cells"],
  ["cell-to-body", "CTB (cell-to-body)"],
  ["8-in-1 electric drive unit", "8-in-1 electric powertrain"],
  ["Hybrid engine 1.5 L, high efficiency", "Xiaoyun 1.5 L hybrid engine"],
  ["Dedicated hybrid transmission", "EHS electric hybrid system"],
  ["Heat-pump thermal module", "Wide-temperature heat pump module"],
  ["Rotating touchscreen 15.6 in", "Rotating touchscreen 15.6 in (DiLink)"],
  ["Plant 2 – Line 3 Launch", "Plant 2 – Seal Line Launch"],
  // public battery sizes
  ["60.5 kWh", "61.4 kWh"],
  ["Battery pack 44.9 kWh", "Blade Battery pack 44.9 kWh"],
  ["Battery pack 18.3 kWh (PHEV)", "Blade Battery pack 18.3 kWh (DM-i)"],
];

function rename(value) {
  if (typeof value === "string") return RENAME.reduce((s, [from, to]) => s.split(from).join(to), value);
  if (Array.isArray(value)) return value.map(rename);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k, v]) => [rename(k), rename(v)]));
  return value;
}

export const CONTEXTS = rename(V.CONTEXTS);
CONTEXTS.vx3[2] =
  "BYD's dedicated EV platform: Blade Battery packs built into the body (CTB), 8-in-1 electric powertrain, heat pump, chassis and electronics.";
CONTEXTS.aurora[2] = "D-segment electric sedan on e-Platform 3.0: rear- or all-wheel drive, 61.4 or 82.5 kWh. In production.";
CONTEXTS.tern[2] = "Compact electric hatchback on e-Platform 3.0, front-wheel drive, 44.9 kWh. In development (test data).";
CONTEXTS.ridge[2] =
  "Plug-in hybrid SUV with DM-i: Xiaoyun 1.5 L engine, EHS hybrid system, 18.3 kWh Blade Battery. In validation (test data).";
CONTEXTS.plant[2] = "Start of production of the Seal on Line 3 of Plant 2: stamping, body, paint, battery and general assembly.";

export const TEAM = V.TEAM;
export const PEOPLE = V.PEOPLE;
export const CLASSES = V.CLASSES;

// Parts, with the public motor figures of the Seal and Dolphin.
const MOTORS = {
  "EP3-ED-150": ["8-in-1 electric powertrain 230 kW (rear)", null],
  "EP3-MT-150": ["Traction motor PMSM 230 kW", 40, { motor_type: "PMSM", peak_power: 230, peak_torque: 360, max_speed: 16000 }],
  "EP3-ED-070": ["Electric powertrain 70 kW (front)", 49],
  "EP3-MT-070": ["Traction motor PMSM 70 kW", 24, { motor_type: "PMSM", peak_power: 70, peak_torque: 180, max_speed: 14000 }],
};
export const PARTS = rename(V.PARTS).map((p) => {
  const m = MOTORS[p[1]];
  if (!m) return p;
  const copy = [...p];
  copy[2] = m[0];
  if (m[1]) copy[3] = { ...copy[3], mass: m[1] };
  if (m[2]) copy[5] = m[2];
  return copy;
});
// The Seal U DM-i is a five-seater: a sliding rear seat instead of a third row.
for (const p of PARTS) {
  if (p[1] === "SLU-IN-340") {
    p[2] = "Rear seat 60/40, sliding";
    p[3] = { ...p[3], mass: 23 };
  }
}

export const STRUCTURES = rename(V.STRUCTURES);
// Its front motor is inside the EHS hybrid system.
STRUCTURES["SLU-0001"] = STRUCTURES["SLU-0001"]
  .filter(([child]) => child !== "EP3-ED-160F")
  .map((l) => (l[0] === "SLU-IN-340" ? ["SLU-IN-340", 1] : l));

export const OPTION_SET = rename(V.OPTION_SET);
OPTION_SET.options[0][3] = [
  ["STD", "Standard range 61.4 kWh"],
  ["LR", "Long range 82.5 kWh"],
];
OPTION_SET.variants = [
  [
    "Seal Design RWD 82.5 kWh",
    { BATTERY: "LR", DRIVE: "RWD", TRIM: "DESIGN", ROOF: "GLASS" },
    "230 kW rear, 570 km WLTP (public figures).",
  ],
  [
    "Seal Excellence AWD 82.5 kWh",
    { BATTERY: "LR", DRIVE: "AWD", TRIM: "EXCELLENCE", ROOF: "GLASS" },
    "390 kW, 0-100 km/h in 3.8 s, 520 km WLTP (public figures).",
  ],
];

export const DOCUMENTS = rename(V.DOCUMENTS).map((d) => {
  const doc = [...d];
  if (doc[2] === "BDOC-SEAL-0001") {
    doc[7] = [
      "BYD Seal: length 4,800 mm, width 1,875 mm, height 1,460 mm, wheelbase 2,920 mm (public figures).",
      "Platform e-Platform 3.0; Blade Battery 82.5 kWh (61.4 kWh in some markets), CTB (cell-to-body).",
      "Design (RWD): 230 kW, 360 Nm, 0-100 km/h 5.9 s, WLTP range about 570 km.",
      "Excellence (AWD): 160 kW front + 230 kW rear, 390 kW, 670 Nm, 0-100 km/h 3.8 s, WLTP about 520 km.",
      "Drag coefficient 0.219. DC fast charging up to 150 kW.",
      "Test data: the rest of this system's records (part numbers, structures, changes) are invented.",
    ];
  }
  return doc;
});

export const CHANGES = rename(V.CHANGES);

export const REPORTS = rename(V.REPORTS);

// Same models and files; numbers and names follow the renames.
export const CAD_MODELS = V.CAD_MODELS.map(([part, file, kind, name]) => [rename(part), file, kind, rename(name)]);

export const KEYS = {
  ...rename(V.KEYS),
  baseline: ["Seal – start of production", "The released Seal structure at SOP, Plant 2 Line 3 (test data)."],
};

export const TOUR = [
  "BYD Seal › SEAL-0001: Structure (options, 150% structure), Options tab, Effectivity tab, 3D view",
  "3D models: the Blade Battery pack (hide its lid in the model tree), 8-in-1 powertrain, cell, wheel, knuckle, bolt",
  "e-Platform 3.0 › EP3-CL-138 (the Blade Battery cell): Where used across three vehicle lines",
  "Changes (top bar): the door-seal problem through to a resolved change notice",
  "Plant 2 – Seal Line Launch: work instructions, PFMEA, control plan, in folders by shop",
  "Classes (top bar): find cells, motors and fasteners by their attributes",
  "Reports (top bar): cells per pack, where a part is used, parts without documents, open problems",
];

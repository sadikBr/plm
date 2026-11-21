// STEP -> binary glTF for the PLM's web viewer (roadmap 6.6).
//   node convert.mjs <in.step> <out.glb>
// Reads the model with OpenCASCADE (occt-import-js, WebAssembly), keeps the assembly tree's names
// (nodes) and colours (materials), and writes one .glb. Prints a JSON summary on stdout.
import occtimportjs from "occt-import-js";
import { readFileSync, writeFileSync } from "node:fs";

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error("usage: node convert.mjs <in.step> <out.glb>");
  process.exit(2);
}

const occt = await occtimportjs();
const result = occt.ReadStepFile(new Uint8Array(readFileSync(input)), {
  linearUnit: "millimeter",
  linearDeflectionType: "bounding_box_ratio",
  linearDeflection: 0.001,
  angularDeflection: 0.5,
});
if (!result.success) {
  console.error("The STEP file couldn't be read");
  process.exit(3);
}

// Buffers: one binary chunk, 4-byte aligned views.
const chunks = [];
let length = 0;
const bufferViews = [];
const accessors = [];
function view(typed, target) {
  const bytes = new Uint8Array(typed.buffer, typed.byteOffset, typed.byteLength);
  const pad = (4 - (length % 4)) % 4;
  if (pad) {
    chunks.push(new Uint8Array(pad));
    length += pad;
  }
  bufferViews.push({ buffer: 0, byteOffset: length, byteLength: bytes.byteLength, target });
  chunks.push(bytes);
  length += bytes.byteLength;
  return bufferViews.length - 1;
}
function accessor(typed, type, componentType, target, minmax) {
  const count = type === "SCALAR" ? typed.length : typed.length / 3;
  const a = { bufferView: view(typed, target), componentType, count, type };
  if (minmax) {
    const min = [Infinity, Infinity, Infinity];
    const max = [-Infinity, -Infinity, -Infinity];
    for (let i = 0; i < typed.length; i += 3) for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], typed[i + k]);
      max[k] = Math.max(max[k], typed[i + k]);
    }
    a.min = min;
    a.max = max;
  }
  accessors.push(a);
  return accessors.length - 1;
}

const materials = [];
const materialIndex = new Map();
function material(color) {
  const c = color ?? [0.72, 0.74, 0.78];
  const key = c.map((v) => v.toFixed(3)).join(",");
  if (!materialIndex.has(key)) {
    materials.push({ pbrMetallicRoughness: { baseColorFactor: [c[0], c[1], c[2], 1], metallicFactor: 0.2, roughnessFactor: 0.6 }, doubleSided: true });
    materialIndex.set(key, materials.length - 1);
  }
  return materialIndex.get(key);
}

let triangles = 0;
const meshes = result.meshes.map((m, i) => {
  const positions = new Float32Array(m.attributes.position.array);
  const primitive = { attributes: { POSITION: accessor(positions, "VEC3", 5126, 34962, true) }, material: material(m.color) };
  if (m.attributes.normal) primitive.attributes.NORMAL = accessor(new Float32Array(m.attributes.normal.array), "VEC3", 5126, 34962, false);
  const index = m.index.array;
  triangles += index.length / 3;
  primitive.indices = accessor(positions.length / 3 > 65535 ? new Uint32Array(index) : new Uint16Array(index), "SCALAR", positions.length / 3 > 65535 ? 5125 : 5123, 34963, false);
  return { name: m.name || `mesh ${i}`, primitives: [primitive] };
});

// The tree: each occt node becomes a glTF node; its meshes become child nodes (world coordinates).
const nodes = [];
function addNode(n) {
  const node = { name: n.name || "", children: [] };
  nodes.push(node);
  const index = nodes.length - 1;
  for (const m of n.meshes) {
    nodes.push({ name: meshes[m].name, mesh: m });
    node.children.push(nodes.length - 1);
  }
  for (const c of n.children) node.children.push(addNode(c));
  if (!node.children.length) delete node.children;
  return index;
}
const root = addNode(result.root);
// STEP is Z-up in millimetres; glTF is Y-up in metres.
nodes[root].matrix = [0.001, 0, 0, 0, 0, 0, -0.001, 0, 0, 0.001, 0, 0, 0, 0, 0, 1];

const gltf = {
  asset: { version: "2.0", generator: "plm-cad-converter (occt-import-js)" },
  scene: 0,
  scenes: [{ nodes: [root] }],
  nodes,
  meshes,
  materials,
  accessors,
  bufferViews,
  buffers: [{ byteLength: length }],
};

let json = Buffer.from(JSON.stringify(gltf));
const jsonPad = (4 - (json.length % 4)) % 4;
json = Buffer.concat([json, Buffer.alloc(jsonPad, 0x20)]);
const bin = Buffer.concat([...chunks.map((c) => Buffer.from(c.buffer, c.byteOffset, c.byteLength)), Buffer.alloc((4 - (length % 4)) % 4)]);
const header = Buffer.alloc(12);
header.writeUInt32LE(0x46546c67, 0);
header.writeUInt32LE(2, 4);
header.writeUInt32LE(12 + 8 + json.length + 8 + bin.length, 8);
const jsonHeader = Buffer.alloc(8);
jsonHeader.writeUInt32LE(json.length, 0);
jsonHeader.writeUInt32LE(0x4e4f534a, 4);
const binHeader = Buffer.alloc(8);
binHeader.writeUInt32LE(bin.length, 0);
binHeader.writeUInt32LE(0x004e4942, 4);
writeFileSync(output, Buffer.concat([header, jsonHeader, json, binHeader, bin]));
console.log(JSON.stringify({ meshes: meshes.length, triangles, nodes: nodes.length, bytes: 28 + json.length + bin.length }));

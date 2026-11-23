"""Tests of the FreeCAD connector on real FreeCAD files (FreeCAD's own examples, see fixtures/README.md).

Offline tests always run; the round trip runs with PLM_TOKEN (a site administrator's) and
PLM_SERVER set, against `./plm start`.
"""
import os
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "..", "..", "sdk", "python"))

from plm_freecad import connector, fcstd  # noqa: E402

FIXTURES = os.path.join(HERE, "fixtures")


class ReadTest(unittest.TestCase):
    def test_an_assembly_links_its_parts_with_names_and_placements(self):
        doc = fcstd.read_document(os.path.join(FIXTURES, "Engine.FCStd"))
        self.assertEqual(doc["kind"], "ASSEMBLY")
        self.assertEqual([l["file"] for l in doc["links"]], ["parts/Pin.FCStd", "parts/Block.FCStd", "parts/Pin.FCStd"])
        pin = doc["links"][0]
        self.assertEqual(pin["label"], "BasePin001")
        self.assertAlmostEqual(pin["transform"][12], -206.517, places=2)
        self.assertEqual(pin["transform"][15], 1.0)

    def test_a_part_has_no_links(self):
        doc = fcstd.read_document(os.path.join(FIXTURES, "parts", "Block.FCStd"))
        self.assertEqual(doc["kind"], "PART")
        self.assertEqual(doc["links"], [])
        self.assertTrue(doc["program"])

    def test_the_manifest_holds_every_linked_document_once_and_only_sends_changes(self):
        base, docs, missing = connector.collect([os.path.join(FIXTURES, "Engine.FCStd")])
        self.assertEqual(missing, [])
        self.assertEqual(sorted(docs), ["Engine.FCStd", "parts/Block.FCStd", "parts/Pin.FCStd"])
        sent = []
        m = connector.manifest(base, docs, {"models": {}}, "ctx", lambda path, data: sent.append(path) or "up-%d" % len(sent))
        self.assertEqual(len(sent), 3)
        engine = next(x for x in m["models"] if x["key"] == "Engine.FCStd")
        self.assertEqual([d["key"] for d in engine["dependencies"]], ["parts/Pin.FCStd", "parts/Block.FCStd", "parts/Pin.FCStd"])
        self.assertTrue(m["application"].startswith("FreeCAD "))
        # Saved before with the same hashes: nothing is sent again.
        state = {"models": {k: {"id": "x", "sha256": d["sha256"]} for k, d in docs.items()}}
        sent.clear()
        connector.manifest(base, docs, state, "ctx", lambda path, data: sent.append(path))
        self.assertEqual(sent, [])


@unittest.skipUnless(os.environ.get("PLM_TOKEN"), "set PLM_TOKEN (and PLM_SERVER) for the round trip")
class RoundTripTest(unittest.TestCase):
    def test_save_an_assembly_change_a_part_save_again_and_open_it_elsewhere(self):
        from plm_connector import PlmConnector

        plm = PlmConnector(os.environ.get("PLM_SERVER", "http://localhost:8080"), os.environ["PLM_TOKEN"])
        suffix = str(int(time.time() * 1000))[-8:]
        site = plm.get("/api/v1/contexts/site")
        org = plm.post("/api/v1/contexts", {"parentId": site["id"], "kind": "ORGANIZATION", "name": f"FreeCAD {suffix}"})
        product = plm.post("/api/v1/contexts", {"parentId": org["id"], "kind": "PRODUCT", "name": f"Engine {suffix}"})
        work = tempfile.mkdtemp()
        shutil.copytree(FIXTURES, work, dirs_exist_ok=True)
        quiet = lambda *a: None  # noqa: E731

        saved = connector.save(plm, [os.path.join(work, "Engine.FCStd")], product["id"], part=True, comment="First", log=quiet)
        self.assertEqual(len(saved), 3)
        self.assertTrue(all(s["created"] for s in saved))
        engine = next(s for s in saved if s["key"] == "Engine.FCStd")
        self.assertTrue(engine["partNumber"])
        files = plm.files(engine["id"])
        self.assertEqual(len(files), 3)
        top = files[0]
        self.assertEqual([d["occurrence"] for d in top["dependencies"]], ["BasePin001", "Stick001", "BucketLink004"])

        # Change one part: only it is sent; the others keep their versions.
        with open(os.path.join(work, "parts", "Pin.FCStd"), "ab") as f:
            f.write(b"\0")
        again = {s["key"]: s for s in connector.save(plm, [os.path.join(work, "Engine.FCStd")], product["id"], log=quiet)}
        self.assertFalse(any(s["created"] for s in again.values()))
        self.assertEqual(again["parts/Pin.FCStd"]["version"], "A.3")
        # Unchanged models keep their version.
        self.assertEqual(again["Engine.FCStd"]["version"], "A.2")
        self.assertEqual(again["parts/Block.FCStd"]["version"], "A.2")

        # Someone else opens it: every file, checked.
        elsewhere = tempfile.mkdtemp()
        opened = connector.open_model(plm, engine["id"], elsewhere, log=quiet)
        self.assertEqual(sorted(f["fileName"] for f in opened), ["Block.FCStd", "Engine.FCStd", "Pin.FCStd"])
        # Where the assembly's links expect them, so FreeCAD finds them.
        self.assertTrue(os.path.exists(os.path.join(elsewhere, "parts", "Pin.FCStd")))
        self.assertTrue(os.path.exists(os.path.join(elsewhere, "parts", "Block.FCStd")))
        # And saving from there is recognised: nothing new is created.
        resaved = connector.save(plm, [os.path.join(elsewhere, "Engine.FCStd")], product["id"], log=quiet)
        self.assertFalse(any(s["created"] for s in resaved))
        self.assertTrue(os.path.exists(os.path.join(elsewhere, ".plm-freecad.json")))


if __name__ == "__main__":
    unittest.main()

"""Tests for signal fusion: no inventing poles, no same-source self-confirmation."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from fuse_sp_traffic_signals import Record, distance, fuse


def base():
    return [
        Record("osm", f"osm:{i + 1}",
               -23.90 + (i // 100) * 0.004,
               -47.00 + (i % 100) * 0.004)
        for i in range(5000)
    ]


class FusionTests(unittest.TestCase):
    def test_only_one_source_preserves_every_osm_node(self):
        original = base()
        merged, report = fuse(original, {})
        self.assertEqual(len(merged), 5000)
        self.assertEqual(report["cross_source_confirmations"], 0)
        self.assertEqual(merged[0][0], "osm:1")

    def test_two_independent_close_points_are_matched(self):
        original = base()
        near = Record("geosampa", "official_10", original[0].lat,
                      original[0].lon + 0.000065)
        merged, report = fuse(original, {"geosampa": [near]})
        self.assertEqual(len(merged), 5000)
        self.assertEqual(report["cross_source_confirmations"], 1)
        key, lat, lon = next(x for x in merged if x[0] == "osm:1")
        self.assertLessEqual(distance(original[0], Record("fused", key, lat, lon)), 5.01)

    def test_ambiguous_near_two_osm_poles_is_not_averaged(self):
        original = base()
        a = original[0]
        original.append(Record("osm", "osm:new", a.lat, a.lon + 0.00018))
        middle = Record("geosampa", "middle", a.lat, a.lon + 0.00009)
        merged, report = fuse(original, {"geosampa": [middle]})
        self.assertEqual(report["ambiguous_not_merged"], 1)
        self.assertEqual(report["cross_source_confirmations"], 0)
        self.assertEqual(len(merged), 5001)

    def test_official_point_far_from_osm_is_added_as_distinct(self):
        original = base()
        extra = Record("geosampa", "city_52", -23.6, -46.6)
        merged, report = fuse(original, {"geosampa": [extra]})
        self.assertEqual(report["additional_distinct_points"], 1)
        self.assertTrue(any(x[0] == "ravo:geosampa:city_52" for x in merged))

    def test_no_duplicate_official_nodes_at_same_osm_pole(self):
        original = base()
        a = original[0]
        extras = [
            Record("geosampa", "official_1", a.lat, a.lon + 0.00004),
            Record("geosampa", "official_2", a.lat, a.lon + 0.00005),
        ]
        merged, report = fuse(original, {"geosampa": extras})
        self.assertEqual(report["cross_source_confirmations"], 1)
        self.assertEqual(report["nearby_duplicates_suppressed"], 1)
        self.assertEqual(len(merged), 5000)


if __name__ == "__main__":
    unittest.main()

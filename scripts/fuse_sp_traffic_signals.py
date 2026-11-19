#!/usr/bin/env python3
"""Conservative geographic fusion for RAVO SP traffic lights.

Inputs are independent source CSV files with id,lat,lon. Never count multiple
mirrors of OSM as independent sources. Merge only unambiguous cross-source
matches; keep distinct poles at the same intersection. Source names must be
audited by maintainers. This is positional reconciliation, not a live signal.
"""
import csv
import io
import json
import math
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

GRID_DEG = 0.00013  # ~14 m latitude; exact proximity uses metric distance.
MATCH_METERS = 12.0  # protect nearby signals on the same intersection.
NEAR_DUPLICATE_METERS = 24.0
MAX_SHIFT_METERS = 5.0
MIN_OSM_POINTS = 5000
OUTPUT = Path("app/src/main/assets/roadalerts/sp_semaforos.csv")
REPORT = Path("data/traffic_quality/sp_semaforos_report.json")
WEIGHTS = {"osm": 1.0, "geosampa": 1.6}


@dataclass(frozen=True)
class Record:
    source: str
    id: str
    lat: float
    lon: float


def distance(a, b):
    """Local equirectangular distance in metres, sufficiently accurate under 30 m."""
    lat = math.radians((a.lat + b.lat) / 2)
    x = math.radians(a.lon - b.lon) * math.cos(lat)
    y = math.radians(a.lat - b.lat)
    return 6371008.8 * math.hypot(x, y)


def read_source(path, name):
    items = []
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        rows = csv.DictReader(handle)
        if not {"id", "lat", "lon"}.issubset(rows.fieldnames or []):
            raise ValueError(f"{path} must contain id,lat,lon")
        ids = set()
        for row in rows:
            key = str(row["id"]).strip()
            lat, lon = float(row["lat"]), float(row["lon"])
            if not key or key in ids:
                continue
            if not (math.isfinite(lat) and math.isfinite(lon) and
                    -25.6 <= lat <= -19.5 and -53.3 <= lon <= -43.5):
                raise ValueError(f"Bad SP coordinate: {path} {key}")
            ids.add(key)
            items.append(Record(name, key, lat, lon))
    return items


def fuse(primary, extras):
    """Return (rows, report). Use one-to-one matching, never same-source merging."""
    if len(primary) < MIN_OSM_POINTS:
        raise ValueError(f"Incomplete base with only {len(primary)} OSM points")
    groups = []
    grid = {}
    counters = Counter()
    raw_counts = {"osm": len(primary)}

    def cell(item):
        return (int(math.floor(item.lat / GRID_DEG)),
                int(math.floor(item.lon / GRID_DEG)))

    def nearby(item, metres):
        i, j = cell(item)
        radius_cells = 4 if metres > 15 else 3
        found = set()
        for di in range(-radius_cells, radius_cells + 1):
            for dj in range(-radius_cells, radius_cells + 1):
                for idx in grid.get((i + di, j + dj), []):
                    if idx not in found and distance(groups[idx]["anchor"], item) <= metres:
                        found.add(idx)
        return [(idx, distance(groups[idx]["anchor"], item)) for idx in found]

    def add_group(item):
        idx = len(groups)
        groups.append({"anchor": item, "members": {item.source: item}})
        grid.setdefault(cell(item), []).append(idx)

    for point in primary:
        if point.source != "osm" or not point.id.startswith("osm:"):
            raise ValueError("Primary IDs must be unique OSM node IDs")
        add_group(point)

    for source, records in sorted(extras.items()):
        if source == "osm":
            raise ValueError("OSM cannot be used twice as independent source")
        if not source.isascii() or not source.replace("_", "").isalnum():
            raise ValueError("Source names must be ASCII alphanumeric or underscore")
        raw_counts[source] = len(records)
        for item in records:
            candidates = sorted((d, idx) for idx, d in nearby(item, MATCH_METERS)
                                if source not in groups[idx]["members"])
            if candidates:
                best_dist, index = candidates[0]
                # Two equally plausible poles in one crossing? Keep original
                # coordinates; do not claim an arbitrary match or make an extra pin.
                if len(candidates) > 1 and candidates[1][0] <= max(8.0, best_dist * 1.6):
                    counters["ambiguous_skipped"] += 1
                    continue
                groups[index]["members"][source] = item
                counters["matched"] += 1
            elif nearby(item, NEAR_DUPLICATE_METERS):
                # Likely nearby stop line or same junction; do not create a duplicate.
                counters["nearby_unmatched_skipped"] += 1
            else:
                add_group(item)
                counters["extra_points"] += 1

    result = []
    multi = 0
    for group in groups:
        anchor = group["anchor"]
        members = group["members"]
        if len(members) > 1:
            multi += 1
            denom = sum(WEIGHTS.get(name, 1.0) for name in members)
            lat = sum(item.lat * WEIGHTS.get(name, 1.0) for name, item in members.items()) / denom
            lon = sum(item.lon * WEIGHTS.get(name, 1.0) for name, item in members.items()) / denom
            estimated = Record("fusion", anchor.id, lat, lon)
            separation = distance(anchor, estimated)
            if separation > MAX_SHIFT_METERS:
                fraction = MAX_SHIFT_METERS / separation
                lat = anchor.lat + (lat - anchor.lat) * fraction
                lon = anchor.lon + (lon - anchor.lon) * fraction
        else:
            lat, lon = anchor.lat, anchor.lon
        # Do not invent IDs: source provenance is encoded in the ID of new pins.
        identifier = anchor.id if anchor.source == "osm" else (
            "ravo:" + anchor.source + ":" + anchor.id
        )
        result.append((identifier, lat, lon))

    report = {
        "primary": "OpenStreetMap (ODbL)",
        "input_counts": raw_counts,
        "output_count": len(result),
        "cross_source_confirmations": multi,
        "matched_records": counters["matched"],
        "ambiguous_not_merged": counters["ambiguous_skipped"],
        "nearby_duplicates_suppressed": counters["nearby_unmatched_skipped"],
        "additional_distinct_points": counters["extra_points"],
        "max_coordinate_shift_m": MAX_SHIFT_METERS,
        "match_radius_m": MATCH_METERS,
        "warning": "Sources may be incomplete. Not all nearby nodes refer to the same device.",
    }
    return sorted(result), report


def save(rows, report, output=OUTPUT, quality=REPORT):
    output.parent.mkdir(parents=True, exist_ok=True)
    quality.parent.mkdir(parents=True, exist_ok=True)
    text = io.StringIO(newline="")
    writer = csv.writer(text, lineterminator="\n")
    writer.writerow(("id", "lat", "lon"))
    for identifier, lat, lon in rows:
        writer.writerow((identifier, f"{lat:.7f}", f"{lon:.7f}"))
    output.write_text(text.getvalue(), encoding="utf-8")
    quality.write_text(json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    print("FUSION REPORT " + json.dumps(report, ensure_ascii=False, sort_keys=True), flush=True)


def main():
    base = read_source(OUTPUT, "osm")
    extra_dir = Path("data/traffic_sources")
    extras = {p.stem: read_source(p, p.stem) for p in extra_dir.glob("*.csv")}
    rows, report = fuse(base, extras)
    save(rows, report)


if __name__ == "__main__":
    main()

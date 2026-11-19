#!/usr/bin/env python3
"""Generate statewide São Paulo traffic-light CSV from mapped OSM nodes (ODbL).

No inferred coordinates. Aborts on incomplete Overpass responses or sudden dataset shrink.
"""
import csv
import io
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

OUTPUT = Path("app/src/main/assets/roadalerts/sp_semaforos.csv")
API_ENDPOINTS = (
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
OVERPASS_QUERY = '''[out:json][timeout:130];
area["ISO3166-2"="BR-SP"]["admin_level"="4"]->.state;
node(area.state)["highway"="traffic_signals"];
out body;
'''
MIN_COUNT = 5000


def fetch_signals():
    payload = urlencode({"data": OVERPASS_QUERY}).encode("utf-8")
    errors = []
    for endpoint in API_ENDPOINTS:
        for attempt in range(2):
            try:
                request = Request(
                    endpoint,
                    data=payload,
                    headers={
                        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept": "application/json",
                        "User-Agent": "RAVO-traffic-signals-import/1.0 (OpenStreetMap ODbL; GitHub Actions)",
                    },
                    method="POST",
                )
                with urlopen(request, timeout=165) as response:
                    body = response.read(15_000_001)
                if len(body) > 15_000_000:
                    raise ValueError("Overpass response exceeds 15 MB")
                parsed = json.loads(body)
                if not isinstance(parsed.get("elements"), list):
                    raise ValueError("No Overpass elements")
                signals = {}
                for element in parsed["elements"]:
                    if element.get("type") != "node":
                        continue
                    lat, lon, osm_id = element.get("lat"), element.get("lon"), element.get("id")
                    if isinstance(osm_id, int) and isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
                        if -25.6 <= lat <= -19.5 and -53.3 <= lon <= -43.5:
                            signals[f"osm:{osm_id}"] = (float(lat), float(lon))
                items = sorted((key, *value) for key, value in signals.items())
                if len(items) < MIN_COUNT:
                    raise ValueError(f"Only {len(items)} traffic signals; possible incomplete query")
                return items
            except Exception as exc:
                errors.append(f"{endpoint} attempt {attempt + 1}: {exc}")
                time.sleep(2)
    raise RuntimeError("No complete statewide dataset obtained: " + " | ".join(errors))



def fetch_from_pbf():
    """Fallback extraction from the free São Paulo state OSM PBF."""
    import osmium
    import urllib.request

    class Signals(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.rows = {}

        def node(self, node):
            if node.tags.get("highway") != "traffic_signals" or not node.location.valid():
                return
            lat, lon = node.location.lat, node.location.lon
            if -25.6 <= lat <= -19.5 and -53.3 <= lon <= -43.5:
                self.rows[f"osm:{node.id}"] = (lat, lon)

    url = "https://geo2day.com/south_america/brazil/southeast/sao-paulo.pbf"
    with tempfile.NamedTemporaryFile(prefix="ravo-sp-", suffix=".osm.pbf", delete=False) as temp:
        filename = temp.name
        try:
            request = Request(url, headers={
                "User-Agent": "RAVO/1.1 (statewide traffic-signal extraction; OSM ODbL)"
            })
            with urlopen(request, timeout=50) as response:
                total = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > 500 * 1024 * 1024:
                        raise RuntimeError("State OSM PBF is larger than expected")
                    temp.write(chunk)
            temp.flush()
            print(f"Downloaded São Paulo PBF: {total:,} bytes", flush=True)
            collector = Signals()
            collector.apply_file(filename)
            items = sorted((key, *value) for key, value in collector.rows.items())
            if len(items) < MIN_COUNT:
                raise RuntimeError(f"Unexpectedly only {len(items)} mapped signals in SP PBF")
            return items
        finally:
            try:
                os.unlink(filename)
            except OSError:
                pass



def existing_count(path):
    if not path.is_file():
        return 0
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        next(reader, None)
        return sum(1 for row in reader if len(row) >= 3)


def main():
    # PBF estadual é mais confiável que uma consulta Overpass gigante.
    try:
        signals = fetch_from_pbf()
    except Exception as error:
        print(f"São Paulo PBF unavailable: {error}; trying Overpass", flush=True)
        signals = fetch_signals()
    previous = existing_count(OUTPUT)
    if previous >= MIN_COUNT and len(signals) < previous * 0.75:
        raise RuntimeError(f"Refusing to shrink data from {previous} to {len(signals)}")
    data = io.StringIO(newline="")
    writer = csv.writer(data, lineterminator="\n")
    writer.writerow(("id", "lat", "lon"))
    for osm_id, lat, lon in signals:
        writer.writerow((osm_id, f"{lat:.7f}", f"{lon:.7f}"))
    content = data.getvalue()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    if OUTPUT.exists() and OUTPUT.read_text(encoding="utf-8") == content:
        print(f"Unchanged: {len(signals)} mapped traffic signals")
        return
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=OUTPUT.parent, delete=False) as handle:
        handle.write(content)
        temporary = Path(handle.name)
    os.replace(temporary, OUTPUT)
    print(f"Saved {len(signals)} mapped signals: {OUTPUT}, {len(content)} bytes")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"### Sao Paulo (OSM) — {len(signals)} mapped signals\n")


if __name__ == "__main__":
    main()

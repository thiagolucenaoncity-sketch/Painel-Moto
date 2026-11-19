#!/usr/bin/env python3
"""Best-effort import of real GeoSampa traffic-light points (São Paulo city only).

The city's WFS may be unavailable; on failure, leave its input file absent.
The fusion pipeline then uses only sources actually retrieved, without claiming
five independent confirmations.
"""
import csv
import io
import json
import os
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

ROOT = "https://wfs.geosampa.prefeitura.sp.gov.br/geoserver/geoportal/wfs"
DEST = Path("data/traffic_sources/geosampa.csv")


def fetch(params, timeout=24):
    request = Request(ROOT + "?" + urlencode(params), headers={
        "Accept": "application/json,application/xml,text/xml,*/*",
        "User-Agent": "RAVO/1.1 (public GeoSampa geodata validation)",
    })
    with urlopen(request, timeout=timeout) as response:
        content = response.read(13_000_001)
    if len(content) > 13_000_000:
        raise ValueError("WFS payload too large")
    return content


def find_layer():
    data = fetch({"service": "WFS", "request": "GetCapabilities", "version": "1.1.0"})
    root = ET.fromstring(data)
    layer_names = []
    for feature in root.iter():
        if feature.tag.split("}")[-1] != "FeatureType":
            continue
        for child in feature:
            if child.tag.split("}")[-1] == "Name" and child.text:
                layer_names.append(child.text.strip())
    matches = [name for name in layer_names if name.lower().split(":")[-1] == "semaforo"]
    if not matches:
        raise ValueError("GeoSampa WFS has no public layer named semaforo")
    return matches[0]


def main():
    layer = find_layer()
    features = []
    for start in range(0, 50000, 10000):
        data = fetch({
            "service": "WFS",
            "request": "GetFeature",
            "version": "2.0.0",
            "typeNames": layer,
            "outputFormat": "application/json",
            "srsName": "EPSG:4326",
            "count": "10000",
            "startIndex": str(start),
        }, timeout=40)
        parsed = json.loads(data)
        if parsed.get("type") != "FeatureCollection" or not isinstance(parsed.get("features"), list):
            raise ValueError("Not a GeoJSON FeatureCollection")
        part = parsed["features"]
        features.extend(part)
        if len(part) < 10000:
            break
    else:
        raise ValueError("Too many GeoSampa points; refusing truncated import")

    rows = {}
    for index, item in enumerate(features):
        geometry = item.get("geometry") or {}
        if geometry.get("type") != "Point":
            continue
        coords = geometry.get("coordinates") or []
        if len(coords) < 2:
            continue
        lon, lat = float(coords[0]), float(coords[1])
        # Strict city bounds; GeoSampa GeoJSON must be WGS84 lon/lat.
        if not (-24.1 <= lat <= -23.25 and -47.15 <= lon <= -46.25):
            continue
        # Stable IDs when supplied by the provider; fallback to quantized coordinate.
        attrs = item.get("properties") or {}
        key = item.get("id") or attrs.get("id") or attrs.get("fid")
        if key is None:
            key = f"xy:{lat:.6f}:{lon:.6f}"
        rows[str(key)] = (lat, lon)
    if len(rows) < 200:
        raise ValueError(f"Only {len(rows)} valid GeoSampa point records; possible wrong layer / CRS")

    DEST.parent.mkdir(parents=True, exist_ok=True)
    text = io.StringIO(newline="")
    writer = csv.writer(text, lineterminator="\n")
    writer.writerow(("id", "lat", "lon"))
    for identifier, (lat, lon) in sorted(rows.items()):
        writer.writerow((identifier, f"{lat:.7f}", f"{lon:.7f}"))
    DEST.write_text(text.getvalue(), encoding="utf-8")
    print(f"GeoSampa official: {len(rows)} city semaforo points, typeName={layer}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"GeoSampa unavailable; no extra source applied: {error}", flush=True)
        if DEST.exists():
            DEST.unlink()

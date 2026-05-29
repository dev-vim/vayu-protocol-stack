"use client";

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import DeckGL from "@deck.gl/react";
import { H3HexagonLayer } from "@deck.gl/geo-layers";
import { Map } from "react-map-gl/maplibre";
import type { PickingInfo } from "@deck.gl/core";
import { cellToParent, getResolution } from "h3-js";
import "maplibre-gl/dist/maplibre-gl.css";
import type { CellData } from "@/lib/types";

// CARTO raster dark tiles — free, no API key, CORS-open PNG CDN.
// (The vector style.json endpoint on basemaps.cartocdn.com requires an Origin
// allowlist; these raster tile subdomains do not.)
const MAP_STYLE = {
  version: 8 as const,
  sources: {
    carto: {
      type: "raster" as const,
      tiles: [
        "https://a.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}@2x.png",
        "https://b.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}@2x.png",
        "https://c.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}@2x.png",
        "https://d.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}@2x.png",
      ],
      tileSize: 256,
      attribution: "© <a href='https://carto.com/attributions'>CARTO</a> © <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a> contributors",
    },
  },
  layers: [
    {
      id: "carto-dark",
      type: "raster" as const,
      source: "carto",
    },
  ],
};

// ── Initial view: Bengaluru, India (where fixture cells live) ───────────────
const INITIAL_VIEW_STATE = {
  longitude: 77.59,
  latitude:  12.97,
  zoom: 11,
  pitch: 0,
  bearing: 0,
};

// ── Zoom → H3 resolution ─────────────────────────────────────────────────────
// Maps deck.gl zoom levels to H3 resolutions, clamped so we never upsample
// beyond the native resolution of the data.
function zoomToH3Res(zoom: number, nativeRes: number): number {
  let res: number;
  if      (zoom >= 12) res = 8;
  else if (zoom >= 10) res = 7;
  else if (zoom >= 8)  res = 6;
  else if (zoom >= 6)  res = 5;
  else if (zoom >= 4)  res = 4;
  else                 res = 3;
  return Math.min(res, nativeRes);
}

// ── Cell aggregation ──────────────────────────────────────────────────────────
// Groups raw cells into their H3 parent at targetRes using reading-count-
// weighted averages for AQI/pollutants and sum for readingCount.
function aggregateCells(cells: CellData[], targetRes: number, nativeRes: number): CellData[] {
  if (cells.length === 0 || targetRes >= nativeRes) return cells;

  type Acc = {
    aqiW: number; pm25W: number; pm10W: number;
    o3W: number; no2W: number; so2W: number; coW: number;
    readingCount: number;
  };
  const groups: Record<string, Acc> = {};

  for (const c of cells) {
    let parent: string;
    try { parent = cellToParent(c.h3Index, targetRes); }
    catch { continue; }

    const w = c.readingCount;
    const g = groups[parent];
    if (g) {
      g.aqiW  += c.medianAqi * w;  g.pm25W += c.avgPm25 * w;
      g.pm10W += c.avgPm10  * w;  g.o3W   += c.avgO3   * w;
      g.no2W  += c.avgNo2   * w;  g.so2W  += c.avgSo2  * w;
      g.coW   += c.avgCo    * w;  g.readingCount += w;
    } else {
      groups[parent] = {
        aqiW: c.medianAqi * w, pm25W: c.avgPm25 * w, pm10W: c.avgPm10 * w,
        o3W:  c.avgO3     * w, no2W:  c.avgNo2  * w, so2W:  c.avgSo2  * w,
        coW:  c.avgCo     * w, readingCount: w,
      };
    }
  }

  return Object.entries(groups).map(([h3Index, g]) => {
    const n = g.readingCount || 1;
    return {
      h3Index,
      medianAqi:    Math.round(g.aqiW  / n),
      readingCount: g.readingCount,
      avgPm25:      Math.round(g.pm25W / n),
      avgPm10:      Math.round(g.pm10W / n),
      avgO3:        Math.round(g.o3W   / n),
      avgNo2:       Math.round(g.no2W  / n),
      avgSo2:       Math.round(g.so2W  / n),
      avgCo:        Math.round(g.coW   / n),
    };
  });
}

// ── AQI colour scale (US EPA breakpoints) ────────────────────────────────────
// Tune HEX_ALPHA (0–255) to adjust peak fill intensity at the hex centre:
//   ~60  → light / mostly transparent
//   ~110 → balanced (default)
//   ~180 → vivid / opaque
const HEX_ALPHA = 50;

// ── Radial gradient controls ──────────────────────────────────────────────────
// RADIAL_STEPS: concentric coverage rings composited per cell.
//   1  → flat fill (gradient disabled)
//   3  → subtle halo
//   5  → smooth gradient (default)
//   8+ → very smooth, more draw calls
// RADIAL_FALLOFF: alpha distribution curve (exponent applied to the 0→1 ramp).
//   0.5 → shallow / wide glow
//   1.0 → linear
//   1.5 → moderate centre spike (default)
//   2.5 → sharp centre spike
const RADIAL_STEPS   = 4;
const RADIAL_FALLOFF = 2.0;

function aqiColor(aqi: number, alpha = HEX_ALPHA): [number, number, number, number] {
  if (aqi <= 50)  return [0,   228,   0, alpha];   // Good
  if (aqi <= 100) return [255, 255,   0, alpha];   // Moderate
  if (aqi <= 150) return [255, 126,   0, alpha];   // Unhealthy for sensitive groups
  if (aqi <= 200) return [255,   0,   0, alpha];   // Unhealthy
  if (aqi <= 300) return [143,  63, 151, alpha];   // Very unhealthy
  return                 [126,   0,  35, alpha];   // Hazardous
}

// ── Tooltip renderer ─────────────────────────────────────────────────────────
function renderTooltip(info: PickingInfo<CellData>) {
  if (!info.object) return null;
  const d = info.object;
  const [r, g, b] = aqiColor(d.medianAqi);
  return {
    html: `
      <div style="
        background:#18181b;
        border:1px solid #3f3f46;
        border-radius:8px;
        padding:10px 14px;
        font-size:12px;
        line-height:1.7;
        color:#e4e4e7;
        font-family:monospace;
      ">
        <div style="font-weight:600;color:#a1a1aa;margin-bottom:4px">H3 cell</div>
        <div><span style="color:#71717a">index:</span> ${d.h3Index}</div>
        <div>
          <span style="color:#71717a">AQI:</span>&nbsp;
          <span style="color:rgb(${r},${g},${b});font-weight:700">${d.medianAqi}</span>
        </div>
        <div><span style="color:#71717a">readings:</span> ${d.readingCount}</div>
        <div><span style="color:#71717a">PM2.5:</span> ${d.avgPm25} µg/m³</div>
        <div><span style="color:#71717a">PM10:</span>  ${d.avgPm10} µg/m³</div>
        ${d.avgO3  ? `<div><span style="color:#71717a">O₃:</span>   ${d.avgO3}  ppb</div>` : ""}
        ${d.avgNo2 ? `<div><span style="color:#71717a">NO₂:</span>  ${d.avgNo2} ppb</div>` : ""}
      </div>
    `,
  };
}

// ── AQI legend entries ───────────────────────────────────────────────────────
const LEGEND = [
  { label: "Good (0–50)",           color: "rgb(0,228,0)"  },
  { label: "Moderate (51–100)",     color: "rgb(255,255,0)" },
  { label: "USG (101–150)",         color: "rgb(255,126,0)" },
  { label: "Unhealthy (151–200)",   color: "rgb(255,0,0)"   },
  { label: "Very unhealthy (201–300)", color: "rgb(143,63,151)" },
  { label: "Hazardous (301+)",      color: "rgb(126,0,35)"  },
];

// ── Props ────────────────────────────────────────────────────────────────────
interface Props {
  /** Ordered list of epochs (newest first) shown in the selector. */
  epochs: { epochId: number; ipfsStatus: string }[];
  /** Base URL of the Ponder indexer (e.g. http://localhost:42069). */
  indexerUrl: string;
  /** Pre-loaded cells per epoch — used when MOCK_DATA=true. */
  mockCellsByEpoch?: Record<number, CellData[]>;
}

// ── Component ────────────────────────────────────────────────────────────────
export default function EpochHexMap({
  epochs,
  indexerUrl,
  mockCellsByEpoch,
}: Props) {
  // Only offer INGESTED epochs (they have cell_epochs rows)
  const ingestedEpochs = epochs.filter((e) => e.ipfsStatus === "INGESTED");

  const [selectedEpochId, setSelectedEpochId] = useState<number | null>(
    ingestedEpochs[0]?.epochId ?? null,
  );
  const [cells, setCells] = useState<CellData[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Detect native H3 resolution from first cell (stable per epoch load)
  const nativeRes = useMemo(() => {
    if (cells.length === 0) return 8;
    try { return getResolution(cells[0].h3Index); } catch { return 8; }
  }, [cells]);

  // Current target resolution — updated only when crossing a zoom boundary
  const [targetRes, setTargetRes] = useState(() =>
    zoomToH3Res(INITIAL_VIEW_STATE.zoom, 8),
  );
  const nativeResRef = useRef(nativeRes);
  nativeResRef.current = nativeRes;

  // Aggregated cells for the active resolution
  const displayCells = useMemo(
    () => aggregateCells(cells, targetRes, nativeRes),
    [cells, targetRes, nativeRes],
  );

  const isAggregated = targetRes < nativeRes && cells.length > 0;

  const loadCells = useCallback(
    async (epochId: number) => {
      // ── Mock path ───────────────────────────────────────────────────────────
      if (mockCellsByEpoch) {
        setCells(mockCellsByEpoch[epochId] ?? []);
        return;
      }

      // ── Live path ────────────────────────────────────────────────────────────
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(`${indexerUrl}/epochs/${epochId}/cells`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        setCells(json.cells ?? []);
      } catch (err) {
        setError(err instanceof Error ? err.message : "fetch failed");
        setCells([]);
      } finally {
        setLoading(false);
      }
    },
    [indexerUrl, mockCellsByEpoch],
  );

  useEffect(() => {
    if (selectedEpochId !== null) loadCells(selectedEpochId);
  }, [selectedEpochId, loadCells]);

  // Build RADIAL_STEPS concentric layers. i=0 is outermost (coverage=1, low alpha);
  // i=RADIAL_STEPS-1 is innermost (smallest coverage, full HEX_ALPHA). Only the
  // outermost layer is pickable/stroked so tooltips fire anywhere on the hexagon.
  const layers = Array.from({ length: RADIAL_STEPS }, (_, i) => {
    const t        = (i + 1) / RADIAL_STEPS;          // 0 < t ≤ 1, increases toward centre
    const coverage = 1 - i / RADIAL_STEPS;            // 1.0 → 1/RADIAL_STEPS
    const alpha    = Math.round(HEX_ALPHA * Math.pow(t, RADIAL_FALLOFF));
    const isOuter  = i === 0;

    return new H3HexagonLayer<CellData>({
      id:            `cell-aqi-${i}`,
      data:          displayCells,
      getHexagon:    (d) => d.h3Index,
      getFillColor:  (d) => aqiColor(d.medianAqi, alpha),
      coverage,
      extruded:      false,
      stroked:       isOuter,
      getLineColor:  [255, 255, 255, 20],
      lineWidthMinPixels: 1,
      pickable:      isOuter,
      autoHighlight: isOuter,
      highlightColor: [255, 255, 255, 40],
      transitions:   { getFillColor: 300 },
      updateTriggers: { getFillColor: displayCells.length, data: targetRes },
    });
  });

  return (
    <div className="rounded-xl border border-zinc-800 overflow-hidden">
      {/* ── Epoch selector ─────────────────────────────────────────────────── */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-zinc-800 bg-zinc-900/60">
        <label
          htmlFor="epoch-select"
          className="text-xs text-zinc-500 uppercase tracking-widest shrink-0"
        >
          Epoch
        </label>
        <select
          id="epoch-select"
          value={selectedEpochId ?? ""}
          onChange={(e) =>
            setSelectedEpochId(e.target.value ? Number(e.target.value) : null)
          }
          className="bg-zinc-800 border border-zinc-700 rounded-md text-zinc-200 text-sm px-2 py-1 focus:outline-none focus:ring-1 focus:ring-teal-500"
        >
          {ingestedEpochs.length === 0 && (
            <option value="">No ingested epochs</option>
          )}
          {ingestedEpochs.map((e) => (
            <option key={e.epochId} value={e.epochId}>
              #{e.epochId}
            </option>
          ))}
        </select>

        {loading && (
          <span className="text-xs text-zinc-500 animate-pulse">loading…</span>
        )}
        {error && (
          <span className="text-xs text-red-400">Error: {error}</span>
        )}
        {!loading && !error && cells.length > 0 && (
          <span className="text-xs text-zinc-500">
            {displayCells.length} cell{displayCells.length !== 1 ? "s" : ""}
            {isAggregated && (
              <span className="ml-1.5 px-1.5 py-0.5 rounded bg-zinc-700/60 text-zinc-400">
                res {targetRes}
              </span>
            )}
          </span>
        )}
        {!loading && !error && cells.length === 0 && selectedEpochId !== null && (
          <span className="text-xs text-zinc-600">no cell data</span>
        )}
      </div>

      {/* ── Map ────────────────────────────────────────────────────────────── */}
      <div style={{ position: "relative", height: 480 }}>
        <DeckGL
          initialViewState={INITIAL_VIEW_STATE}
          controller
          layers={layers}
          getTooltip={renderTooltip}
          style={{ position: "absolute", top: "0", right: "0", bottom: "0", left: "0" }}
          onViewStateChange={({ viewState }) => {
            const { zoom } = viewState as { zoom: number };
            setTargetRes(zoomToH3Res(zoom, nativeResRef.current));
          }}
        >
          <Map mapStyle={MAP_STYLE} />
        </DeckGL>

        {/* ── AQI legend ───────────────────────────────────────────────────── */}
        <div
          style={{ position: "absolute", bottom: 16, right: 12 }}
          className="bg-zinc-900/90 border border-zinc-700 rounded-lg px-3 py-2 text-xs space-y-1"
        >
          {LEGEND.map((l) => (
            <div key={l.label} className="flex items-center gap-2">
              <span
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: 2,
                  background: l.color,
                  flexShrink: 0,
                  display: "inline-block",
                }}
              />
              <span className="text-zinc-400">{l.label}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

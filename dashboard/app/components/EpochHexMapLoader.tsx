"use client";

// Thin client wrapper that owns the dynamic import of EpochHexMap.
// `ssr: false` is only valid inside a Client Component in Next.js 15.

import dynamic from "next/dynamic";
import type { ComponentProps } from "react";
import type EpochHexMapType from "./EpochHexMap";

const EpochHexMap = dynamic(() => import("./EpochHexMap"), { ssr: false });

export default function EpochHexMapLoader(
  props: ComponentProps<typeof EpochHexMapType>,
) {
  return <EpochHexMap {...props} />;
}

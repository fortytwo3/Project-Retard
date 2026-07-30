import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  images: {
    remotePatterns: [
      { protocol: "https", hostname: "images.pokemontcg.io" },
      { protocol: "https", hostname: "i.ebayimg.com" },
      { protocol: "https", hostname: "www.pricecharting.com" },
    ],
  },
  // tesseract.js ships a node build that Next tries to bundle into the browser
  // chunk. It is only ever imported from client components, so keep webpack
  // from following the node-only requires.
  webpack(config) {
    config.resolve.fallback = { ...config.resolve.fallback, fs: false, path: false };
    return config;
  },
};

export default nextConfig;

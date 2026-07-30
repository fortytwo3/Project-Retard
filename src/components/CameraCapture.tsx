"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/** Standard trading card: 2.5in × 3.5in. */
const CARD_ASPECT = 2.5 / 3.5;

interface Props {
  /** Receives the cropped card image, ready for OCR. */
  onCapture: (canvas: HTMLCanvasElement) => void;
  disabled?: boolean;
}

type CameraState = "idle" | "starting" | "live" | "denied" | "unsupported";

/**
 * Live camera view with a card-shaped guide.
 *
 * Cropping to the guide rather than sending the whole frame to OCR is what
 * makes the read reliable — it removes the table, your hand, and whatever else
 * is in shot, and it means the region fractions in the OCR client can assume
 * the frame is exactly one card.
 */
export function CameraCapture({ onCapture, disabled }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const guideRef = useRef<HTMLDivElement>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const [state, setState] = useState<CameraState>("idle");
  const [error, setError] = useState<string | null>(null);

  const stop = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  const start = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setState("unsupported");
      return;
    }

    setState("starting");
    setError(null);

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: "environment" },
          // Ask for a high-resolution frame; collector numbers are ~2mm tall
          // and a 720p capture often will not resolve them.
          width: { ideal: 1920 },
          height: { ideal: 1080 },
        },
        audio: false,
      });

      streamRef.current = stream;

      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }

      setState("live");
    } catch (err) {
      const name = err instanceof DOMException ? err.name : "";
      if (name === "NotAllowedError" || name === "SecurityError") {
        setState("denied");
        setError(
          "Camera access was blocked. Allow it in your browser settings, or upload a photo instead.",
        );
      } else {
        setState("unsupported");
        setError(
          err instanceof Error ? err.message : "This device did not provide a camera stream.",
        );
      }
    }
  }, []);

  // Release the camera when the component goes away — a live stream keeps the
  // hardware indicator lit and drains the battery.
  useEffect(() => stop, [stop]);

  /**
   * Copy the guide region out of the video frame.
   *
   * The video is rendered with `object-fit: cover`, so the displayed frame is
   * scaled and centre-cropped relative to its intrinsic size. Both transforms
   * have to be undone to find the source pixels under the guide box.
   */
  const capture = useCallback(() => {
    const video = videoRef.current;
    const container = containerRef.current;
    const guide = guideRef.current;
    if (!video || !container || !guide || !video.videoWidth) return;

    const containerRect = container.getBoundingClientRect();
    const guideRect = guide.getBoundingClientRect();

    const scale = Math.max(
      containerRect.width / video.videoWidth,
      containerRect.height / video.videoHeight,
    );

    const offsetX = (containerRect.width - video.videoWidth * scale) / 2;
    const offsetY = (containerRect.height - video.videoHeight * scale) / 2;

    const sx = (guideRect.left - containerRect.left - offsetX) / scale;
    const sy = (guideRect.top - containerRect.top - offsetY) / scale;
    const sw = guideRect.width / scale;
    const sh = guideRect.height / scale;

    const canvas = document.createElement("canvas");
    canvas.width = Math.round(sw);
    canvas.height = Math.round(sh);

    const context = canvas.getContext("2d");
    if (!context) return;

    context.drawImage(video, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
    onCapture(canvas);
  }, [onCapture]);

  /** Fallback path: pick an existing photo. Also how desktop users scan. */
  const handleFile = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0];
      if (!file) return;

      const image = new Image();
      const url = URL.createObjectURL(file);

      image.onload = () => {
        const canvas = document.createElement("canvas");
        canvas.width = image.naturalWidth;
        canvas.height = image.naturalHeight;
        canvas.getContext("2d")?.drawImage(image, 0, 0);
        URL.revokeObjectURL(url);
        onCapture(canvas);
      };
      image.onerror = () => {
        URL.revokeObjectURL(url);
        setError("Could not read that image file.");
      };

      image.src = url;
      // Reset so picking the same file twice still fires a change event.
      event.target.value = "";
    },
    [onCapture],
  );

  return (
    <div className="space-y-3">
      <div
        ref={containerRef}
        className="relative aspect-3/4 w-full overflow-hidden rounded-2xl border border-line bg-black"
      >
        <video
          ref={videoRef}
          playsInline
          muted
          className={`size-full object-cover ${state === "live" ? "" : "opacity-0"}`}
        />

        {/* Card-shaped guide. Pointer-events off so the shutter stays tappable. */}
        <div className="pointer-events-none absolute inset-0 grid place-items-center">
          <div
            ref={guideRef}
            style={{ aspectRatio: CARD_ASPECT, width: "78%" }}
            className="relative rounded-xl shadow-[0_0_0_9999px_rgba(0,0,0,0.55)]"
          >
            <div className="absolute inset-0 rounded-xl border-2 border-accent/80" />
            {/* Bands marking where the OCR crops land, so the user knows what
                actually has to be legible. */}
            <div className="absolute inset-x-0 top-[2%] h-[16%] border-y border-dashed border-accent/35" />
            <div className="absolute inset-x-0 bottom-0 h-[17%] border-y border-dashed border-accent/35" />
          </div>
        </div>

        {state !== "live" && (
          <div className="absolute inset-0 grid place-items-center p-6 text-center">
            {state === "idle" && (
              <button type="button" onClick={start} className="btn btn-primary">
                Turn on camera
              </button>
            )}
            {state === "starting" && <p className="text-sm text-muted">Starting camera…</p>}
            {(state === "denied" || state === "unsupported") && (
              <p className="max-w-xs text-sm text-muted">{error}</p>
            )}
          </div>
        )}
      </div>

      <div className="flex gap-2">
        <button
          type="button"
          onClick={capture}
          disabled={state !== "live" || disabled}
          className="btn btn-primary flex-1"
        >
          Scan card
        </button>

        <label className="btn btn-secondary cursor-pointer">
          Upload
          <input
            type="file"
            accept="image/*"
            capture="environment"
            onChange={handleFile}
            className="sr-only"
            disabled={disabled}
          />
        </label>
      </div>

      {state === "live" && (
        <p className="text-center text-xs text-muted">
          Fill the frame with the card. The name and the number in the bottom corner both need to be
          readable.
        </p>
      )}
    </div>
  );
}

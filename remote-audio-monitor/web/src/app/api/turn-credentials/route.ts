import { NextResponse } from "next/server";

// Server-side only — METERED_SECRET_KEY never reaches the browser or the
// Android app. Both clients call this route instead of Metered directly.
const METERED_DOMAIN = "remote-audio-monitor.metered.live";

export async function GET() {
  const secretKey = process.env.METERED_SECRET_KEY;

  if (!secretKey) {
    return NextResponse.json(
      { error: "TURN credential service not configured" },
      { status: 500 }
    );
  }

  try {
    const response = await fetch(
      `https://${METERED_DOMAIN}/api/v1/turn/credential?apiKey=${secretKey}`
    );

    if (!response.ok) {
      return NextResponse.json(
        { error: "Failed to fetch TURN credentials" },
        { status: 502 }
      );
    }

    const iceServers = await response.json();

    // Google's free public STUN, plus Metered's TURN credentials.
    const combined = [
      { urls: "stun:stun.l.google.com:19302" },
      ...iceServers,
    ];

    return NextResponse.json({ iceServers: combined });
  } catch {
    return NextResponse.json(
      { error: "TURN credential service unreachable" },
      { status: 502 }
    );
  }
}
